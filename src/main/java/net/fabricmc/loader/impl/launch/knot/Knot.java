/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.loader.impl.launch.knot;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import org.spongepowered.asm.launch.MixinBootstrap;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.entrypoint.EntrypointUtils;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.GameProviders;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.launch.FabricMixinBootstrap;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.loader.impl.util.UrlUtil;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

public final class Knot extends FabricLauncherBase {
	protected Map<String, Object> properties = new HashMap<>();

	private KnotClassLoaderInterface classLoader;
	private boolean isDevelopment;
	private EnvType envType;
	private final File gameJarFile;
	private GameProvider provider;

	public static void launch(String[] args, EnvType type) {
		String gameJarPath = System.getProperty(SystemProperties.GAME_JAR_PATH);
		Knot knot = new Knot(type, gameJarPath != null ? new File(gameJarPath) : null);
		ClassLoader cl = knot.init(args);

		if (knot.provider == null) {
			throw new IllegalStateException("Game provider was not initialized! (Knot#init(String[]))");
		}

		knot.provider.launch(cl);
	}

	public Knot(EnvType type, File gameJarFile) {
		this.envType = type;
		this.gameJarFile = gameJarFile;
	}

	protected ClassLoader init(String[] args) {
		setProperties(properties);

		// configure fabric vars
		if (envType == null) {
			String side = System.getProperty(SystemProperties.SIDE);
			if (side == null) throw new RuntimeException("Please specify side or use a dedicated Knot!");

			switch (side.toLowerCase(Locale.ROOT)) {
			case "client":
				envType = EnvType.CLIENT;
				break;
			case "server":
				envType = EnvType.SERVER;
				break;
			default:
				throw new RuntimeException("Invalid side provided: must be \"client\" or \"server\"!");
			}
		}

		// TODO: Restore these undocumented features
		// String proposedEntrypoint = System.getProperty("fabric.loader.entrypoint");

		List<GameProvider> providers = GameProviders.create();
		provider = null;

		for (GameProvider p : providers) {
			if (p.locateGame(envType, args, this.getClass().getClassLoader())) {
				provider = p;
				break;
			}
		}

		if (provider != null) {
			Log.info(LogCategory.GAME_PROVIDER, "Loading for game %s %s", provider.getGameName(), provider.getRawGameVersion());
		} else {
			Log.error(LogCategory.GAME_PROVIDER, "Could not find valid game provider!");

			for (GameProvider p : providers) {
				Log.error(LogCategory.GAME_PROVIDER, "- %s", p.getGameName());
			}

			throw new RuntimeException("Could not find valid game provider!");
		}

		isDevelopment = Boolean.parseBoolean(System.getProperty(SystemProperties.DEVELOPMENT, "false"));

		// Setup classloader
		// TODO: Provide KnotCompatibilityClassLoader in non-exclusive-Fabric pre-1.13 environments?
		boolean useCompatibility = provider.requiresUrlClassLoader() || Boolean.parseBoolean(System.getProperty("fabric.loader.useCompatibilityClassLoader", "false"));
		classLoader = useCompatibility ? new KnotCompatibilityClassLoader(isDevelopment(), envType, provider) : new KnotClassLoader(isDevelopment(), envType, provider);
		ClassLoader cl = (ClassLoader) classLoader;

		if (provider.isObfuscated()) {
			for (Path path : provider.getGameContextJars()) {
				FabricLauncherBase.deobfuscate(
						provider.getGameId(), provider.getNormalizedGameVersion(),
						provider.getLaunchDirectory(),
						path,
						this);
			}
		}

		// Locate entrypoints before switching class loaders
		provider.getEntrypointTransformer().locateEntrypoints(this);

		Thread.currentThread().setContextClassLoader(cl);

		FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
		loader.setGameProvider(provider);
		loader.load();
		loader.freeze();

		FabricLoaderImpl.INSTANCE.loadAccessWideners();

		MixinBootstrap.init();
		FabricMixinBootstrap.init(getEnvironmentType(), loader);
		FabricLauncherBase.finishMixinBootstrapping();

		classLoader.getDelegate().initializeTransformers();

		EntrypointUtils.invoke("preLaunch", PreLaunchEntrypoint.class, PreLaunchEntrypoint::onPreLaunch);

		return cl;
	}

	@Override
	public String getTargetNamespace() {
		// TODO: Won't work outside of Yarn
		return isDevelopment ? "named" : "intermediary";
	}

	@Override
	public Collection<URL> getLoadTimeDependencies() {
		String cmdLineClasspath = System.getProperty("java.class.path");

		return Arrays.stream(cmdLineClasspath.split(File.pathSeparator)).filter((s) -> {
			if (s.equals("*") || s.endsWith(File.separator + "*")) {
				Log.warn(LogCategory.KNOT, "Knot does not support wildcard classpath entries: %s - the game may not load properly!", s);
				return false;
			} else {
				return true;
			}
		}).map((s) -> {
			File file = new File(s);

			if (!file.equals(gameJarFile)) {
				try {
					return (UrlUtil.asUrl(file));
				} catch (MalformedURLException e) {
					Log.debug(LogCategory.KNOT, "Can't determine url for %s", file, e);
					return null;
				}
			} else {
				return null;
			}
		}).filter(Objects::nonNull).collect(Collectors.toSet());
	}

	@Override
	public void addToClassPath(Path path) {
		Log.debug(LogCategory.KNOT, "Adding " + path + " to classpath.");

		try {
			classLoader.addURL(UrlUtil.asUrl(path));
		} catch (MalformedURLException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public EnvType getEnvironmentType() {
		return envType;
	}

	@Override
	public boolean isClassLoaded(String name) {
		return classLoader.isClassLoaded(name);
	}

	@Override
	public InputStream getResourceAsStream(String name) {
		try {
			return classLoader.getResourceAsStream(name, false);
		} catch (IOException e) {
			throw new RuntimeException("Failed to read file '" + name + "'!", e);
		}
	}

	@Override
	public ClassLoader getTargetClassLoader() {
		return (ClassLoader) classLoader;
	}

	@Override
	public byte[] getClassByteArray(String name, boolean runTransformers) throws IOException {
		if (runTransformers) {
			return classLoader.getDelegate().getPreMixinClassByteArray(name, false);
		} else {
			return classLoader.getDelegate().getRawClassByteArray(name, false);
		}
	}

	@Override
	public Manifest getManifest(Path originPath) {
		try {
			return classLoader.getDelegate().getMetadata(UrlUtil.asUrl(originPath)).manifest;
		} catch (MalformedURLException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public boolean isDevelopment() {
		return isDevelopment;
	}

	@Override
	public String getEntrypoint() {
		return provider.getEntrypoint();
	}

	public static void main(String[] args) {
		new Knot(null, null).init(args);
	}
}
