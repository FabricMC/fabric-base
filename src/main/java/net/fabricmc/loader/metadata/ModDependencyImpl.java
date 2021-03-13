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

package net.fabricmc.loader.metadata;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.VersionPredicate;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.util.version.VersionPredicateParser;

final class ModDependencyImpl implements ModDependency {
	private final String modId;
	private final ModEnvironment environment;
	private final String reason;
	private final List<String> matcherStringList;
	private Set<VersionPredicate> ranges;

	ModDependencyImpl(String modId, ModEnvironment environment, @Nullable String reason, List<String> matcherStringList) {
		this.modId = modId;
		this.environment = environment;
		this.reason = reason;
		this.matcherStringList = matcherStringList;
	}

	@Override
	public String getModId() {
		return this.modId;
	}

	@Override
	public Optional<String> getReason() {
		return Optional.ofNullable(this.reason);
	}

	@Override
	public ModEnvironment getEnvironment() {
		return this.environment;
	}

	@Override
	public boolean matches(Version version) {
		for (String s : this.matcherStringList) {
			try {
				if (VersionPredicateParser.matches(version, s)) {
					return true;
				}
			} catch (VersionParsingException e) {
				e.printStackTrace();
				return false;
			}
		}

		return false;
	}

	@Override
	public String toString() {
		final StringBuilder builder = new StringBuilder("{");
		builder.append(this.modId);
		builder.append(" @ [");

		for (int i = 0; i < matcherStringList.size(); i++) {
			if (i > 0) {
				builder.append(" || ");
			}

			builder.append(matcherStringList.get(i));
		}

		builder.append("]}");
		return builder.toString();
	}

	@Override
	public Set<VersionPredicate> getVersionRequirements() {
		if (ranges == null) {
			ranges = VersionPredicate.parse(matcherStringList);
		}

		return ranges;
	}
}
