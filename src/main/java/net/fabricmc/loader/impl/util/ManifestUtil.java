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

package net.fabricmc.loader.impl.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes.Name;
import java.util.jar.Manifest;

public final class ManifestUtil {
	public static final Name NAME_MAPPING_NAMESPACE = new Name("Fabric-Mapping-Namespace");
	public static final Name NAME_MIXIN_GROUP = new Name("Fabric-Mixin-Group");
	public static final Name NAME_MIXIN_VERSION = new Name("Fabric-Mixin-Version");

	public static Manifest readManifest(Path basePath) throws IOException {
		Path path = basePath.resolve("META-INF").resolve("MANIFEST.MF");
		if (!Files.exists(path)) return null;

		try (InputStream stream = Files.newInputStream(path)) {
			return new Manifest(stream);
		}
	}

	public static String getManifestValue(Manifest manifest, Name name) {
		return manifest.getMainAttributes().getValue(name);
	}
}
