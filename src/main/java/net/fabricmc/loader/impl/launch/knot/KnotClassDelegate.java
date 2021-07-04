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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.cert.Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.Manifest;

import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.transformer.FabricTransformer;
import net.fabricmc.loader.impl.util.FileSystemUtil;
import net.fabricmc.loader.impl.util.UrlConversionException;
import net.fabricmc.loader.impl.util.UrlUtil;

class KnotClassDelegate {
	static class Metadata {
		static final Metadata EMPTY = new Metadata(null, null);

		final Manifest manifest;
		final CodeSource codeSource;

		Metadata(Manifest manifest, CodeSource codeSource) {
			this.manifest = manifest;
			this.codeSource = codeSource;
		}
	}

	private final Map<String, Metadata> metadataCache = new HashMap<>();
	private final KnotClassLoaderInterface itf;
	private final GameProvider provider;
	private final boolean isDevelopment;
	private final EnvType envType;
	private IMixinTransformer mixinTransformer;
	private boolean transformInitialized = false;

	KnotClassDelegate(boolean isDevelopment, EnvType envType, KnotClassLoaderInterface itf, GameProvider provider) {
		this.isDevelopment = isDevelopment;
		this.envType = envType;
		this.itf = itf;
		this.provider = provider;
	}

	public void initializeTransformers() {
		if (transformInitialized) throw new IllegalStateException("Cannot initialize KnotClassDelegate twice!");

		mixinTransformer = MixinServiceKnot.getTransformer();
		if (mixinTransformer == null) throw new IllegalStateException("mixin transformer unavailable?");

		transformInitialized = true;
	}

	private IMixinTransformer getMixinTransformer() {
		assert mixinTransformer != null;
		return mixinTransformer;
	}

	Metadata getMetadata(String name, URL resourceURL) {
		if (resourceURL != null) {
			URL codeSourceURL = null;
			String filename = name.replace('.', '/') + ".class";

			try {
				codeSourceURL = UrlUtil.getSource(filename, resourceURL);
			} catch (UrlConversionException e) {
				System.err.println("Could not find code source for " + resourceURL + ": " + e.getMessage());
			}

			if (codeSourceURL != null) {
				return metadataCache.computeIfAbsent(codeSourceURL.toString(), (codeSourceStr) -> {
					Manifest manifest = null;
					CodeSource codeSource = null;
					Certificate[] certificates = null;
					URL fCodeSourceUrl = null;

					try {
						fCodeSourceUrl = new URL(codeSourceStr);
						Path path = UrlUtil.asPath(fCodeSourceUrl);

						if (Files.isRegularFile(path)) {
							URLConnection connection = new URL("jar:" + codeSourceStr + "!/").openConnection();

							if (connection instanceof JarURLConnection) {
								manifest = ((JarURLConnection) connection).getManifest();
								certificates = ((JarURLConnection) connection).getCertificates();
							}

							if (manifest == null) {
								try (FileSystemUtil.FileSystemDelegate jarFs = FileSystemUtil.getJarFileSystem(path, false)) {
									Path manifestPath = jarFs.get().getPath("META-INF/MANIFEST.MF");

									if (Files.exists(manifestPath)) {
										try (InputStream stream = Files.newInputStream(manifestPath)) {
											manifest = new Manifest(stream);

											// TODO
											/* JarEntry codeEntry = codeSourceJar.getJarEntry(filename);

											if (codeEntry != null) {
												codeSource = new CodeSource(codeSourceURL, codeEntry.getCodeSigners());
											} */
										}
									}
								}
							}
						}
					} catch (IOException | FileSystemNotFoundException | URISyntaxException e) {
						if (FabricLauncherBase.getLauncher().isDevelopment()) {
							System.err.println("Failed to load manifest: " + e);
							e.printStackTrace();
						}
					}

					if (codeSource == null) {
						codeSource = new CodeSource(fCodeSourceUrl, certificates);
					}

					return new Metadata(manifest, codeSource);
				});
			}
		}

		return Metadata.EMPTY;
	}

	public byte[] getPostMixinClassByteArray(String name) {
		byte[] transformedClassArray = getPreMixinClassByteArray(name, true);

		if (!transformInitialized || !canTransformClass(name)) {
			return transformedClassArray;
		}

		return getMixinTransformer().transformClassBytes(name, name, transformedClassArray);
	}

	/**
	 * Runs all the class transformers except mixin.
	 */
	public byte[] getPreMixinClassByteArray(String name, boolean skipOriginalLoader) {
		// some of the transformers rely on dot notation
		name = name.replace('/', '.');

		if (!transformInitialized || !canTransformClass(name)) {
			try {
				return getRawClassByteArray(name, skipOriginalLoader);
			} catch (IOException e) {
				throw new RuntimeException("Failed to load class file for '" + name + "'!", e);
			}
		}

		byte[] input = provider.getEntrypointTransformer().transform(name);

		if (input == null) {
			try {
				input = getRawClassByteArray(name, skipOriginalLoader);
			} catch (IOException e) {
				throw new RuntimeException("Failed to load class file for '" + name + "'!", e);
			}
		}

		if (input != null) {
			return FabricTransformer.transform(isDevelopment, envType, name, input);
		}

		return null;
	}

	private static boolean canTransformClass(String name) {
		name = name.replace('/', '.');
		// Blocking Fabric Loader classes is no longer necessary here as they don't exist on the modding class loader
		return /* !"net.fabricmc.api.EnvType".equals(name) && !name.startsWith("net.fabricmc.loader.") && */ !name.startsWith("org.apache.logging.log4j");
	}

	String getClassFileName(String name) {
		return name.replace('.', '/') + ".class";
	}

	public byte[] getRawClassByteArray(String name, boolean skipOriginalLoader) throws IOException {
		String classFile = getClassFileName(name);
		InputStream inputStream = itf.getResourceAsStream(classFile, skipOriginalLoader);
		if (inputStream == null) return null;

		int a = inputStream.available();
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream(a < 32 ? 32768 : a);
		byte[] buffer = new byte[8192];
		int len;

		while ((len = inputStream.read(buffer)) > 0) {
			outputStream.write(buffer, 0, len);
		}

		inputStream.close();
		return outputStream.toByteArray();
	}
}
