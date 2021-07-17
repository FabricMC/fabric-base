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

package net.fabricmc.loader.api;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;

import net.fabricmc.loader.api.metadata.ModMetadata;

/**
 * Represents a mod.
 */
public interface ModContainer {
	/**
	 * Returns the metadata of this mod.
	 */
	ModMetadata getMetadata();

	/**
	 * Returns the root directory of the mod.
	 *
	 * @return the root directory
	 * @deprecated use {@link #getRootPath()} instead
	 */
	@Deprecated
	default Path getRoot() {
		return getRootPath();
	}

	/**
	 * Returns the root directory of the mod.
	 *
	 * <p>It may be the root directory of the mod JAR or the folder of the mod.</p>
	 *
	 * @return the root directory of the mod
	 */
	Path getRootPath();

	/**
	 * Gets an NIO reference to a file inside the JAR.
	 *
	 * <p>The path is not guaranteed to exist!</p>
	 *
	 * @param file The location from root, using {@code /} as a separator.
	 * @return the path to a given file
	 */
	default Path getPath(String file) {
		Path root = getRootPath();
		return root.resolve(file.replace("/", root.getFileSystem().getSeparator()));
	}

	/**
	 * Get the mod containing this mod (nested jar parent).
	 *
	 * @return mod containing this mod or empty if not nested
	 */
	Optional<ModContainer> getContainingMod();

	/**
	 * Get the active mods contained within this mod (nested jar children).
	 *
	 * @return active contained mods within this mod's jar
	 */
	Collection<ModContainer> getContainedMods();
}
