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

package net.fabricmc.loader.impl.launch.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Properties;

import net.fabricmc.loader.impl.launch.knot.KnotServer;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.loader.impl.util.UrlUtil;

public class FabricServerLauncher {
	private static final ClassLoader PARENT_LOADER = FabricServerLauncher.class.getClassLoader();
	private static final String INCLUDED_INSTALLER_META_PROPS_NAME = "fabric-server-meta.properties";
	private static final String MAIN_CLASS = KnotServer.class.getName();

	public static void main(String[] args) {
		boolean dev = Boolean.parseBoolean(System.getProperty(SystemProperties.DEVELOPMENT, "false"));

		if (dev) {
			launch(FabricServerLauncher.class.getClassLoader(), args);
			return;
		}

		try {
			setup(args);
		} catch (Exception e) {
			throw new RuntimeException("Failed to setup Fabric server environment!", e);
		}
	}

	private static void launch(ClassLoader loader, String[] args) {
		try {
			Class<?> c = loader.loadClass(FabricServerLauncher.MAIN_CLASS);
			c.getMethod("main", String[].class).invoke(null, (Object) args);
		} catch (Exception e) {
			throw new RuntimeException("An exception occurred when launching the server!", e);
		}
	}

	private static void setup(String... runArguments) throws IOException {
		File serverJar;

		if (PARENT_LOADER.getResource(INCLUDED_INSTALLER_META_PROPS_NAME) != null) {
			serverJar = getStampedServerJar();
		} else {
			serverJar = getServerJarFromProperties();
		}

		System.setProperty(SystemProperties.GAME_JAR_PATH, serverJar.getAbsolutePath());

		try {
			URLClassLoader newClassLoader = new InjectingURLClassLoader(new URL[] { FabricServerLauncher.class.getProtectionDomain().getCodeSource().getLocation(), UrlUtil.asUrl(serverJar) }, PARENT_LOADER, "com.google.common.jimfs.");
			Thread.currentThread().setContextClassLoader(newClassLoader);
			launch(newClassLoader, runArguments);
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	// Used with the full installer
	private static File getServerJarFromProperties() throws IOException {
		// Pre-load "fabric-server-launcher.properties"
		File propertiesFile = new File("fabric-server-launcher.properties");
		Properties properties = new Properties();

		if (propertiesFile.exists()) {
			try (FileInputStream stream = new FileInputStream(propertiesFile)) {
				properties.load(stream);
			}
		}

		// Most popular Minecraft server hosting platforms do not allow
		// passing arbitrary arguments to the server .JAR. Meanwhile,
		// Mojang's default server filename is "server.jar" as of
		// a few versions... let's use this.
		if (!properties.containsKey("serverJar")) {
			properties.put("serverJar", "server.jar");

			try (FileOutputStream stream = new FileOutputStream(propertiesFile)) {
				properties.store(stream, null);
			}
		}

		File serverJar = new File((String) properties.get("serverJar"));

		if (!serverJar.exists()) {
			System.err.println("Could not find Minecraft server .JAR (" + properties.get("serverJar") + ")!");
			System.err.println();
			System.err.println("Fabric's server-side launcher expects the server .JAR to be provided.");
			System.err.println("You can edit its location in fabric-server-launcher.properties.");
			System.err.println();
			System.err.println("Without the official Minecraft server .JAR, Fabric Loader cannot launch.");
			throw new RuntimeException("Searched for '" + serverJar.getName() + "' but could not find it.");
		}

		return serverJar;
	}

	// Stamped from meta, downloads the server jar
	private static File getStampedServerJar() throws IOException {
		Properties properties = new Properties();

		try (InputStream is = PARENT_LOADER.getResource(INCLUDED_INSTALLER_META_PROPS_NAME).openStream()) {
			properties.load(is);
		} catch (IOException e) {
			throw new IOException("Failed to read stamped installer data", e);
		}

		long serverSize = Long.parseLong(Objects.requireNonNull(properties.getProperty("server-size"), "No such property of name: server-size"));
		String serverName = Objects.requireNonNull(properties.getProperty("server-name"), "No such property of name: server-name");

		Path serverJar = Paths.get(".fabric", "server", serverName);
		boolean valid = false;

		if (Files.exists(serverJar)) {
			long actualSize = Files.size(serverJar);
			valid = actualSize == serverSize;

			if (!valid) {
				System.out.printf("Unexpected server file size, found %d bytes expected %d bytes%n", actualSize, serverSize);
			}
		}

		if (!valid) {
			String serverUrl = Objects.requireNonNull(properties.getProperty("server-url"), "No such property of name: server-url");
			System.out.println("Downloading " + serverUrl);

			try {
				Files.createDirectories(serverJar.getParent());

				try (InputStream is = new URL(serverUrl).openStream()) {
					Files.copy(is, serverJar, StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (IOException e) {
				throw new IOException("Failed to download server from" + serverUrl);
			}
		}

		return serverJar.toFile();
	}
}
