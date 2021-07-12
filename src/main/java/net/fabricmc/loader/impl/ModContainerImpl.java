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

package net.fabricmc.loader.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.util.FileSystemUtil;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

@SuppressWarnings("deprecation")
public class ModContainerImpl extends net.fabricmc.loader.ModContainer {
	private final LoaderModMetadata info;
	private final Path originPath;
	private volatile Path root;

	public ModContainerImpl(LoaderModMetadata info, Path originPath) {
		this.info = info;
		this.originPath = originPath;
	}

	@Override
	public LoaderModMetadata getMetadata() {
		return info;
	}

	@Override
	protected Path getOriginPath() {
		return originPath;
	}

	@Override
	public Path getRootPath() {
		Path ret = root;

		if (ret == null || !ret.getFileSystem().isOpen()) {
			if (ret != null && !warned) {
				if (!FabricLoaderImpl.INSTANCE.isDevelopmentEnvironment()) warned = true;
				Log.warn(LogCategory.GENERAL, "FileSystem for %s has been closed unexpectedly, existing root path references may break!", this);
			}

			root = ret = obtainRootPath(); // obtainRootPath is thread safe, but we need to avoid plain or repeated reads to root
		}

		return ret;
	}

	private boolean warned = false;

	private Path obtainRootPath() {
		try {
			if (Files.isDirectory(originPath)) {
				return originPath;
			} else /* JAR */ {
				FileSystemUtil.FileSystemDelegate delegate = FileSystemUtil.getJarFileSystem(originPath, false);

				if (delegate.get() == null) {
					throw new RuntimeException("Could not open JAR file " + originPath + " for NIO reading!");
				}

				return delegate.get().getRootDirectories().iterator().next();

				// We never close here. It's fine. getJarFileSystem() will handle it gracefully, and so should mods
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to find root directory for mod '" + info.getId() + "'!", e);
		}
	}

	@Deprecated
	@Override
	public LoaderModMetadata getInfo() {
		return info;
	}

	@Override
	public String toString() {
		return String.format("%s %s", info.getId(), info.getVersion());
	}
}
