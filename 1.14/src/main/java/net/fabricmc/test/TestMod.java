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

package net.fabricmc.test;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.launch.common.FabricLauncherBase;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class TestMod implements PreLaunchEntrypoint, ModInitializer {

	private static final Logger LOGGER = LogManager.getFormatterLogger("TestMod");

	/**
	 * Entrypoint implementation for preLaunch.
	 *
	 * <p>Warning: This should normally be in a separate class from later entrypoints to avoid accidentally loading
	 * and/or initializing game classes. This is just trivial test code not meant for production use.
	 */
	@Override
	public void onPreLaunch() {
		if (TestMod.class.getClassLoader() != FabricLauncherBase.getLauncher().getTargetClassLoader()) {
			throw new IllegalStateException("invalid class loader: "+TestMod.class.getClassLoader());
		}

		LOGGER.info("In preLaunch (cl "+TestMod.class.getClassLoader()+")");
	}

	@Override
	public void onInitialize() {
		if (TestMod.class.getClassLoader() != FabricLauncherBase.getLauncher().getTargetClassLoader()) {
			throw new IllegalStateException("invalid class loader: "+TestMod.class.getClassLoader());
		}

		LOGGER.info("**************************");
		LOGGER.info("Hello from Fabric");
		LOGGER.info("**************************");
	}

}
