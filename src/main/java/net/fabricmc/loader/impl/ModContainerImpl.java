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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.impl.discovery.ModCandidate;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.util.FileSystemUtil;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

@SuppressWarnings("deprecation")
public class ModContainerImpl extends net.fabricmc.loader.ModContainer {
	private final LoaderModMetadata info;
	private final Path originPath;
	private final String parentModId;
	private final Collection<String> childModIds;

	private volatile Path root;

	public ModContainerImpl(ModCandidate candidate) {
		this.info = candidate.getMetadata();
		this.originPath = candidate.getPath();
		this.parentModId = candidate.getParentMods().isEmpty() ? null : candidate.getParentMods().iterator().next().getId();
		this.childModIds = candidate.getNestedMods().isEmpty() ? Collections.emptyList() : new ArrayList<>(candidate.getNestedMods().size());

		for (ModCandidate c : candidate.getNestedMods()) {
			if (c.getParentMods().size() <= 1 || c.getParentMods().iterator().next() == candidate) {
				childModIds.add(c.getId());
			}
		}
	}

	@Override
	public ModMetadata getMetadata() {
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

	@Override
	public Optional<ModContainer> getContainingMod() {
		return parentModId != null ? FabricLoaderImpl.INSTANCE.getModContainer(parentModId) : Optional.empty();
	}

	@Override
	public Collection<ModContainer> getContainedMods() {
		if (childModIds.isEmpty()) return Collections.emptyList();

		List<ModContainer> ret = new ArrayList<>(childModIds.size());

		for (String id : childModIds) {
			ModContainer mod = FabricLoaderImpl.INSTANCE.getModContainer(id).orElse(null);
			if (mod != null) ret.add(mod);
		}

		return ret;
	}

	@Override
	public LoaderModMetadata getInfo() {
		return info;
	}

	@Override
	public String toString() {
		return String.format("%s %s", info.getId(), info.getVersion());
	}
}
