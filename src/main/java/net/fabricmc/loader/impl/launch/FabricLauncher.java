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

package net.fabricmc.loader.impl.launch;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collection;
import java.util.jar.Manifest;

import net.fabricmc.api.EnvType;

public interface FabricLauncher {
	MappingConfiguration getMappingConfiguration();

	void addToClassPath(Path path);

	EnvType getEnvironmentType();

	boolean isClassLoaded(String name);

	InputStream getResourceAsStream(String name);

	ClassLoader getTargetClassLoader();

	/**
	 * Gets the byte array for a particular class.
	 *
	 * @param name The name of the class to retrieve
	 * @param runTransformers Whether to run all transformers <i>except mixin</i> on the class
	 */
	byte[] getClassByteArray(String name, boolean runTransformers) throws IOException;

	Manifest getManifest(Path originPath);

	boolean isDevelopment();

	String getEntrypoint();

	String getTargetNamespace();

	Collection<URL> getLoadTimeDependencies();
}
