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
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModDependency.Kind;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.fabricmc.loader.impl.util.mappings.MixinIntermediaryDevRemapper;
import net.fabricmc.loader.impl.util.version.SemanticVersionImpl;
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
				dataMap.put(FabricData.KEY_COMPATIBILITY, getMixinCompat(mod));

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

	private static final SemanticVersion LAST_LOADER_MIXIN_0_9_2 = new SemanticVersionImpl(new int[] { 0, 11, 9999 }, null, null);

	private static int getMixinCompat(ModContainerImpl mod) {
		// TODO: check version recorded in the mod's manifest

		// no manifest record, try to infer from loader dependency being >= 0.12.x (first with fabrix-mixin 0.9.4+)

		for (ModDependency dep : mod.getMetadata().getDependencies()) {
			if (dep.getKind() == Kind.DEPENDS && (dep.getModId().equals("fabricloader") || dep.getModId().equals("fabric-loader"))) {
				if (!dep.matches(LAST_LOADER_MIXIN_0_9_2)) { // not satisfied by a loader version with the old mixin version
					return FabricData.COMPATIBILITY_0_9_4;
				}
			}
		}

		// default to old fabric-mixin

		return FabricData.COMPATIBILITY_0_9_2;
	}
}
