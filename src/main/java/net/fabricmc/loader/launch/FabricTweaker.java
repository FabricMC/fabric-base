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

package net.fabricmc.loader.launch;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.entrypoint.minecraft.hooks.EntrypointUtils;
import net.fabricmc.loader.game.GameProvider;
import net.fabricmc.loader.game.MinecraftGameProvider;
import net.fabricmc.loader.launch.common.FabricLauncherBase;
import net.fabricmc.loader.launch.common.FabricMixinBootstrap;
import net.fabricmc.loader.util.UrlConversionException;
import net.fabricmc.loader.util.UrlUtil;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.Collection;
import java.util.List;

public abstract class FabricTweaker extends FabricLauncherBase implements ITweaker {
	protected static Logger LOGGER = LogManager.getFormatterLogger("FabricLoader");
	protected String[] arguments;
	private LaunchClassLoader launchClassLoader;
	private boolean isDevelopment;

	@Override
	public String getEntrypoint() {
		return getLaunchTarget();
	}

	@Override
	public String getTargetNamespace() {
		// TODO: Won't work outside of Yarn
		return isDevelopment ? "named" : "intermediary";
	}

	@Override
	public void acceptOptions(List<String> localArgs, File gameDir, File assetsDir, String profile) {
		arguments = localArgs.toArray(new String[0]);
		/*arguments.parse(localArgs);

		if (!arguments.containsKey("gameDir") && gameDir != null) {
			arguments.put("gameDir", gameDir.getAbsolutePath());
		}

		if (getEnvironmentType() == EnvType.CLIENT && !arguments.containsKey("assetsDir") && assetsDir != null) {
			arguments.put("assetsDir", assetsDir.getAbsolutePath());
		}

		FabricLauncherBase.processArgumentMap(arguments, getEnvironmentType());*/
	}

	@Override
	public void injectIntoClassLoader(LaunchClassLoader launchClassLoader) {
		isDevelopment = Boolean.parseBoolean(System.getProperty("fabric.development", "false"));
		Launch.blackboard.put("fabric.development", isDevelopment);
		setProperties(Launch.blackboard);

		this.launchClassLoader = launchClassLoader;
		launchClassLoader.addClassLoaderExclusion("org.objectweb.asm.");
		launchClassLoader.addClassLoaderExclusion("org.spongepowered.asm.");
		launchClassLoader.addClassLoaderExclusion("net.fabricmc.loader.");

		launchClassLoader.addClassLoaderExclusion("net.fabricmc.api.Environment");
		launchClassLoader.addClassLoaderExclusion("net.fabricmc.api.EnvType");

		GameProvider provider = new MinecraftGameProvider();
		provider.acceptArguments(arguments);

		if (!provider.locateGame(getEnvironmentType(), launchClassLoader)) {
			throw new RuntimeException("Could not locate Minecraft: provider locate failed");
		}

		@SuppressWarnings("deprecation")
		FabricLoader loader = FabricLoader.INSTANCE;
		loader.setGameProvider(provider);
		loader.load();
		loader.freeze();

		launchClassLoader.registerTransformer("net.fabricmc.loader.launch.FabricClassTransformer");

		if (!isDevelopment) {
			// Obfuscated environment
			Launch.blackboard.put("fabric.development", false);
			try {
				String target = getLaunchTarget();
				URL loc = launchClassLoader.findResource(target.replace('.', '/') + ".class");
				JarURLConnection locConn = (JarURLConnection) loc.openConnection();
				File jarFile = UrlUtil.asFile(locConn.getJarFileURL());
				if (!jarFile.exists()) {
					throw new RuntimeException("Could not locate Minecraft: " + jarFile.getAbsolutePath() + " not found");
				}

				FabricLauncherBase.deobfuscate(provider.getGameId(), provider.getNormalizedGameVersion(), provider.getLaunchDirectory(), jarFile.toPath(), this);
			} catch (IOException | UrlConversionException e) {
				throw new RuntimeException("Failed to deobfuscate Minecraft!", e);
			}
		}

		FabricLoader.INSTANCE.getAccessWidener().loadFromMods();

		MinecraftGameProvider.TRANSFORMER.locateEntrypoints(this);

		// Setup Mixin environment
		MixinBootstrap.init();
		FabricMixinBootstrap.init(getEnvironmentType(), FabricLoader.INSTANCE);
		MixinEnvironment.getDefaultEnvironment().setSide(getEnvironmentType() == EnvType.CLIENT ? MixinEnvironment.Side.CLIENT : MixinEnvironment.Side.SERVER);

		EntrypointUtils.invoke("preLaunch", PreLaunchEntrypoint.class, PreLaunchEntrypoint::onPreLaunch);
	}

	@Override
	public String[] getLaunchArguments() {
		return arguments;
	}

	@Override
	public void propose(URL url) {
		launchClassLoader.addURL(url);
	}

	@Override
	public Collection<URL> getLoadTimeDependencies() {
		return launchClassLoader.getSources();
	}

	@Override
	public boolean isClassLoaded(String name) {
		throw new RuntimeException("TODO isClassLoaded/launchwrapper");
	}

	@Override
	public InputStream getResourceAsStream(String name) {
		return launchClassLoader.getResourceAsStream(name);
	}

	@Override
	public ClassLoader getTargetClassLoader() {
		return launchClassLoader;
	}

	@Override
	public byte[] getClassByteArray(String name, boolean runTransformers) throws IOException {
		if (runTransformers) {
			// TODO: we need a way of applying the class transformers without applying mixins
			throw new UnsupportedOperationException("TODO getClassByteArray/launchwrapper");
		}

		String transformedName = name.replace('/', '.');
		byte[] classBytes = launchClassLoader.getClassBytes(name);

//		if (runTransformers && !classLoaderUtil.isClassExcluded(name, transformedName)) {
//			for (IClassTransformer transformer : launchClassLoader.getTransformers()) {
//				classBytes = transformer.transform(name, transformedName, classBytes);
//			}
//		}

		return classBytes;
	}

	@Override
	public boolean isDevelopment() {
		return isDevelopment;
	}
}
