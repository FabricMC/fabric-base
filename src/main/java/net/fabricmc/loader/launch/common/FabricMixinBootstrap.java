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

package net.fabricmc.loader.launch.common;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.metadata.LoaderModMetadata;
import net.fabricmc.loader.util.mappings.MixinIntermediaryDevRemapper;
import net.fabricmc.mapping.tree.TinyTree;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class FabricMixinBootstrap {
	private FabricMixinBootstrap() {

	}

	protected static Logger LOGGER = LogManager.getFormatterLogger("FabricMixinBootstrap");
	private static boolean initialized = false;

	static void addConfiguration(String configuration) {
		Mixins.addConfiguration(configuration);
	}

	static Set<String> getMixinConfigs(FabricLoader loader, EnvType type) {
		return loader.getAllMods().stream()
			.map(ModContainer::getMetadata)
			.filter((m) -> m instanceof LoaderModMetadata)
			.flatMap((m) -> ((LoaderModMetadata) m).getMixinConfigs(type).stream())
			.filter(s -> s != null && !s.isEmpty())
			.collect(Collectors.toSet());
	}

	public static void init(EnvType side, FabricLoader loader) {
		if (initialized) {
			throw new RuntimeException("FabricMixinBootstrap has already been initialized!");
		}

		if (FabricLauncherBase.getLauncher().isDevelopment()) {
			MappingConfiguration mappingConfiguration = FabricLauncherBase.getLauncher().getMappingConfiguration();
			TinyTree mappings = mappingConfiguration.getMappings();

			if (mappings != null) {
				List<String> namespaces = mappings.getMetadata().getNamespaces();
				if (namespaces.contains("intermediary") && namespaces.contains(mappingConfiguration.getTargetNamespace())) {
					System.setProperty("mixin.env.remapRefMap", "true");

					try {
						MixinIntermediaryDevRemapper remapper = new MixinIntermediaryDevRemapper(mappings, "intermediary", mappingConfiguration.getTargetNamespace());
						MixinEnvironment.getDefaultEnvironment().getRemappers().add(remapper);
						LOGGER.info("Loaded Fabric development mappings for mixin remapper!");
					} catch (Exception e) {
						LOGGER.error("Fabric development environment setup error - the game will probably crash soon!");
						e.printStackTrace();
					}
				}
			}
		}

		MixinBootstrap.init();
		getMixinConfigs(loader, side).forEach(FabricMixinBootstrap::addConfiguration);
		initialized = true;
	}
}
