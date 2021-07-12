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

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.FabricData;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.fabricmc.loader.impl.util.mappings.MixinIntermediaryDevRemapper;
import net.fabricmc.mapping.tree.TinyTree;

public final class FabricMixinBootstrap {
	private FabricMixinBootstrap() { }

	private static boolean initialized = false;

	public static void init(EnvType side, FabricLoaderImpl loader) {
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
						Log.info(LogCategory.MIXIN, "Loaded Fabric development mappings for mixin remapper!");
					} catch (Exception e) {
						Log.error(LogCategory.MIXIN, "Fabric development environment setup error - the game will probably crash soon!");
						e.printStackTrace();
					}
				}
			}
		}

		MixinBootstrap.init();

		try {
			Method addConfigurationFabric = Mixins.class.getMethod("addConfigurationFabric", String.class, Map.class);
			Map<String, Object> dataMap = new HashMap<>();

			for (ModContainerImpl mod : loader.getModsInternal()) {
				LoaderModMetadata metadata = mod.getMetadata();
				Collection<String> configs = metadata.getMixinConfigs(side);
				if (configs.isEmpty()) continue;

				dataMap.put(FabricData.KEY_MOD_ID, metadata.getId());

				for (String config : configs) {
					addConfigurationFabric.invoke(null, config, dataMap);
				}
			}
		} catch (NoSuchMethodException e) { // fallback for non-fabric Mixin version
			for (ModContainerImpl mod : loader.getModsInternal()) {
				for (String config : mod.getMetadata().getMixinConfigs(side)) {
					Mixins.addConfiguration(config);
				}
			}
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}

		initialized = true;
	}
}
