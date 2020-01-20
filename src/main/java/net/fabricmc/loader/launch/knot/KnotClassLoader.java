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

package net.fabricmc.loader.launch.knot;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.discovery.ModResolver;
import net.fabricmc.loader.game.GameProvider;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.SecureClassLoader;
import java.util.Enumeration;
import java.util.Objects;
import java.util.jar.Attributes;

class KnotClassLoader extends SecureClassLoader implements KnotClassLoaderInterface {
	private static class DynamicURLClassLoader extends URLClassLoader {
		private DynamicURLClassLoader(URL[] urls) {
			super(urls, new DummyClassLoader());
		}

		@Override
		public void addURL(URL url) {
			super.addURL(url);
		}

		static {
			registerAsParallelCapable();
		}
	}

	private final DynamicURLClassLoader urlLoader;
	private final ClassLoader originalLoader;
	private final KnotClassDelegate delegate;

	KnotClassLoader(boolean isDevelopment, EnvType envType, GameProvider provider) {
		super(new DynamicURLClassLoader(new URL[0]));
		this.originalLoader = getClass().getClassLoader();
		this.urlLoader = (DynamicURLClassLoader) getParent();
		this.delegate = new KnotClassDelegate(isDevelopment, envType, this, provider);
	}

	@Override
	public KnotClassDelegate getDelegate() {
		return delegate;
	}

	@Override
	public boolean isClassLoaded(String name) {
		synchronized (getClassLoadingLock(name)) {
			return findLoadedClass(name) != null;
		}
	}

	@Override
	public URL getResource(String name) {
		Objects.requireNonNull(name);

		URL url = urlLoader.getResource(name);
		if (url == null) {
			url = originalLoader.getResource(name);
		}
		return url;
	}

	@Override
	public InputStream getResourceAsStream(String name) {
		Objects.requireNonNull(name);

		InputStream inputStream = urlLoader.getResourceAsStream(name);
		if (inputStream == null) {
			inputStream = originalLoader.getResourceAsStream(name);
		}
		return inputStream;
	}

	@Override
	public Enumeration<URL> getResources(String name) throws IOException {
		Objects.requireNonNull(name);

		Enumeration<URL> first = urlLoader.getResources(name);
		Enumeration<URL> second = originalLoader.getResources(name);
		return new Enumeration<URL>() {
			Enumeration<URL> current = first;

			@Override
			public boolean hasMoreElements() {
				if (current == null) {
					return false;
				}

				if (current.hasMoreElements()) {
					return true;
				}

				if (current == first && second.hasMoreElements()) {
					return true;
				}

				return false;
			}

			@Override
			public URL nextElement() {
				if (current == null) {
					return null;
				}

				if (!current.hasMoreElements()) {
					if (current == first) {
						current = second;
					} else {
						current = null;
						return null;
					}
				}

				return current.nextElement();
			}
		};
	}

	@Override
	protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		synchronized (getClassLoadingLock(name)) {
			Class<?> c = findLoadedClass(name);

			if (c == null && !name.startsWith("com.google.gson.")) { // FIXME: remove the GSON exclusion once loader stops using it (or repackages it)
				byte[] input = delegate.loadClassData(name, resolve);
				if (input != null) {
					KnotClassDelegate.Metadata metadata = delegate.getMetadata(name, urlLoader.getResource(delegate.getClassFileName(name)));

					int pkgDelimiterPos = name.lastIndexOf('.');
					if (pkgDelimiterPos > 0) {

						String pkgName = name.substring(0, pkgDelimiterPos);
						Package currentPackage = getPackage(pkgName);

						if (currentPackage != null) {
							if (currentPackage.isSealed()) {
								if (!currentPackage.isSealed(metadata.codeSource.getLocation())) {
									KnotClassDelegate.Metadata realMeta = delegate.packages.get(currentPackage);
									URL source = realMeta == null ? null : realMeta.codeSource.getLocation();
									throw new SecurityException("Cannot load the class " + name + " from "
										+ ModResolver.describeRealLocation(metadata.codeSource.getLocation())
										+ " because its package has already been loaded (and sealed) from "
										+ ModResolver.describeRealLocation(source));
								}
							} else if (metadata.isPackageSealed(pkgName)) {
								KnotClassDelegate.Metadata realMeta = delegate.packages.get(currentPackage);
								URL source = realMeta == null ? null : realMeta.codeSource.getLocation();
								throw new SecurityException("Cannot load the class " + name
									+ " (and seal its package) from "
									+ ModResolver.describeRealLocation(metadata.codeSource.getLocation())
									+ " because its package has already been loaded (but not sealed) from"
									+ ModResolver.describeRealLocation(source));
							}
						} else {
							definePackage(pkgName, metadata);
						}
					}

					c = defineClass(name, input, 0, input.length, metadata.codeSource);
				}
			}

			if (c == null) {
				c = originalLoader.loadClass(name);
			}

			if (resolve) {
				resolveClass(c);
			}

			return c;
		}
	}

	private void definePackage(String pkgName, KnotClassDelegate.Metadata metadata) {
		String implVendor = metadata.getPackageValue(pkgName, Attributes.Name.IMPLEMENTATION_VENDOR);
		String implVersion = metadata.getPackageValue(pkgName, Attributes.Name.IMPLEMENTATION_VERSION);
		String implTitle = metadata.getPackageValue(pkgName, Attributes.Name.IMPLEMENTATION_TITLE);
		String specVendor = metadata.getPackageValue(pkgName, Attributes.Name.SPECIFICATION_VENDOR);
		String specVersion = metadata.getPackageValue(pkgName, Attributes.Name.SPECIFICATION_VERSION);
		String specTitle = metadata.getPackageValue(pkgName, Attributes.Name.SPECIFICATION_TITLE);
		URL sealBase = metadata.isPackageSealed(pkgName) ? metadata.codeSource.getLocation() : null;
		Package pkg = definePackage(pkgName, specTitle, specVersion, specVendor, implTitle, implVersion, implVendor, sealBase);
		delegate.packages.put(pkg, metadata);
	}

	@Override
	public void addURL(URL url) {
		urlLoader.addURL(url);
	}

	static {
		registerAsParallelCapable();
	}

	@Override
	public InputStream getResourceAsStream(String classFile, boolean skipOriginalLoader) throws IOException {
		InputStream inputStream = urlLoader.getResourceAsStream(classFile);
		if (inputStream == null && !skipOriginalLoader) {
			inputStream = originalLoader.getResourceAsStream(classFile);
		}
		return inputStream;
	}
}
