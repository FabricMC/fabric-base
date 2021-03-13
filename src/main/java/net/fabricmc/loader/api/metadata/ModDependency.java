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

package net.fabricmc.loader.api.metadata;

import java.util.Optional;
import java.util.Set;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionPredicate;

/**
 * Represents a dependency.
 */
public interface ModDependency {
	/**
	 * Returns the ID of the mod to check.
	 */
	String getModId();

	/**
	 * @return the reason for the mod dependency.
	 */
	Optional<String> getReason();

	/**
	 * @return the mod environment in which this dependency applies.
	 */
	ModEnvironment getEnvironment();

	/**
	 * Returns if the version fulfills this dependency's version requirement.
	 *
	 * @param version the version to check
	 */
	boolean matches(Version version);

	/**
	 * Returns a representation of the dependency's version requirements.
	 *
	 * @return representation of the dependency's version requirements
	 */
	Set<VersionPredicate> getVersionRequirements();
}
