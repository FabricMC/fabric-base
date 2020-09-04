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

package net.fabricmc.loader.entrypoint.minecraft.hooks;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class EntrypointBranding {
	public static final String FABRIC = "fabric";
	public static final String VANILLA = "vanilla";

	private static final Logger LOGGER = LogManager.getLogger("FabricLoader");

	private EntrypointBranding() {
	}

	public static String brand(final String brand) {
		if (brand == null || brand.isEmpty()) {
			LOGGER.warn("Null or empty branding found!", new IllegalStateException());
			return FABRIC;
		}
		return VANILLA.equals(brand) ? FABRIC : brand + ',' + FABRIC;
	}
}
