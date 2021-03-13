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

package net.fabricmc.loader.discovery;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.common.jimfs.PathType;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.FabricLoader;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.game.GameProvider.BuiltinMod;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.launch.common.FabricLauncherBase;
import net.fabricmc.loader.lib.gson.MalformedJsonException;
import net.fabricmc.loader.metadata.BuiltinModMetadata;
import net.fabricmc.loader.metadata.LoaderModMetadata;
import net.fabricmc.loader.metadata.ModMetadataParser;
import net.fabricmc.loader.metadata.NestedJarEntry;
import net.fabricmc.loader.metadata.ParseMetadataException;
import net.fabricmc.loader.util.FileSystemUtil;
import net.fabricmc.loader.util.UrlConversionException;
import net.fabricmc.loader.util.UrlUtil;
import net.fabricmc.loader.util.sat4j.core.VecInt;
import net.fabricmc.loader.util.sat4j.minisat.SolverFactory;
import net.fabricmc.loader.util.sat4j.specs.ContradictionException;
import net.fabricmc.loader.util.sat4j.specs.IProblem;
import net.fabricmc.loader.util.sat4j.specs.ISolver;
import net.fabricmc.loader.util.sat4j.specs.IVecInt;
import net.fabricmc.loader.util.sat4j.specs.TimeoutException;

import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.ZipError;

import static com.google.common.jimfs.Feature.FILE_CHANNEL;
import static com.google.common.jimfs.Feature.SECURE_DIRECTORY_STREAM;

public class ModResolver {
	// nested JAR store
	private static final FileSystem inMemoryFs = Jimfs.newFileSystem(
		"nestedJarStore",
		Configuration.builder(PathType.unix())
			.setRoots("/")
			.setWorkingDirectory("/")
			.setAttributeViews("basic")
			.setSupportedFeatures(SECURE_DIRECTORY_STREAM, FILE_CHANNEL)
			.build()
	);
	private static final Map<String, List<Path>> inMemoryCache = new ConcurrentHashMap<>();
	private static final Pattern MOD_ID_PATTERN = Pattern.compile("[a-z][a-z0-9-_]{1,63}");
	private static final Object launcherSyncObject = new Object();

	private final List<ModCandidateFinder> candidateFinders = new ArrayList<>();

	public ModResolver() {
	}

	public void addCandidateFinder(ModCandidateFinder f) {
		candidateFinders.add(f);
	}

	private static IVecInt toVecInt(IntStream stream) {
		return new VecInt(stream.toArray());
	}

	// TODO: Find a way to sort versions of mods by suggestions and conflicts (not crucial, though)
	public Map<String, ModCandidate> findCompatibleSet(Logger logger, EnvType envType, Map<String, ModCandidateSet> modCandidateSetMap) throws ModResolutionException {
		// First, map all ModCandidateSets to Set<ModCandidate>s.
		boolean isAdvanced = false;
		Map<String, List<ModCandidate>> modCandidateMap = new HashMap<>();
		Set<String> mandatoryMods = new HashSet<>();

		for (ModCandidateSet mcs : modCandidateSetMap.values()) {
			Collection<ModCandidate> s = mcs.toSortedSet();
			modCandidateMap.computeIfAbsent(mcs.getModId(), i -> new ArrayList<>()).addAll(s);
			for (String modProvide : mcs.getModProvides()) {
				modCandidateMap.computeIfAbsent(modProvide, i -> new ArrayList<>()).addAll(s);
			}
			isAdvanced |= (s.size() > 1) || (s.iterator().next().getDepth() > 0);

			if (mcs.isUserProvided()) {
				mandatoryMods.add(mcs.getModId());
			}
		}

		Map<String, ModCandidate> result;

		if (!isAdvanced) {
			result = new HashMap<>();
			for (String s : modCandidateMap.keySet()) {
				ModCandidate candidate = modCandidateMap.get(s).iterator().next();
				// if the candidate isn't actually just a provided alias, then put it on
				if(!candidate.getInfo().getProvides().contains(s)) result.put(s, candidate);
			}
		} else {
			// Inspired by http://0install.net/solver.html
			// probably also horrendously slow, for now

			// Map all the ModCandidates to DIMACS-format positive integers.
			int varCount = 1;
			Map<ModCandidate, Integer> candidateIntMap = new HashMap<>();
			List<ModCandidate> intCandidateMap = new ArrayList<>(modCandidateMap.size() * 2);
			intCandidateMap.add(null);
			for (Collection<ModCandidate> m : modCandidateMap.values()) {
				for (ModCandidate candidate : m) {
					candidateIntMap.put(candidate, varCount++);
					intCandidateMap.add(candidate);
				}
			}

			ISolver solver = SolverFactory.newLight();
			solver.newVar(varCount);

			try {
				// Each mod needs to have at most one version.
				for (String id : modCandidateMap.keySet()) {
					IVecInt versionVec = toVecInt(modCandidateMap.get(id).stream().mapToInt(candidateIntMap::get));

					try {
						if (mandatoryMods.contains(id)) {
							solver.addExactly(versionVec, 1);
						} else {
							solver.addAtMost(versionVec, 1);
						}
					} catch (ContradictionException e) {
						throw new ModResolutionException("Could not resolve valid mod collection (at: adding mod " + id + ")", e);
					}
				}

				for (ModCandidate mod : candidateIntMap.keySet()) {
					int modClauseId = candidateIntMap.get(mod);

					// Each mod's requirements must be satisfied, if it is to be present.
					// mod => ((a or b) AND (d or e))
					// \> not mod OR ((a or b) AND (d or e))
					// \> ((not mod OR a OR b) AND (not mod OR d OR e))

					for (ModDependency dep : mod.getInfo().getDepends()) {
						int[] matchingCandidates = modCandidateMap.getOrDefault(dep.getModId(), Collections.emptyList())
							.stream()
							.filter((c) -> {
								// Consider the environment in which the dependency needs to apply.
								// If the dependency does not apply in the current environment type - then evaluate so the dependency is true
								return !dep.getEnvironment().matches(envType) && dep.matches(c.getInfo().getVersion());
							})
							.mapToInt(candidateIntMap::get)
							.toArray();

						int[] clause = new int[matchingCandidates.length + 1];
						System.arraycopy(matchingCandidates, 0, clause, 0, matchingCandidates.length);
						clause[matchingCandidates.length] = -modClauseId;

						try {
							solver.addClause(new VecInt(clause));
						} catch (ContradictionException e) {
							//TODO: JiJ'd mods don't manage to throw this exception and instead fail silently
							throw new ModResolutionException("Could not find required mod: " + mod.getInfo().getId() + " requires " + dep, e);
						}
					}

					// Each mod's breaks must be NOT satisfied, if it is to be present.
					// mod => (not a AND not b AND not d AND not e))
					// \> not mod OR (not a AND not b AND not d AND not e)
					// \> (not mod OR not a) AND (not mod OR not b) ...

					for (ModDependency dep : mod.getInfo().getBreaks()) {
						int[] matchingCandidates = modCandidateMap.getOrDefault(dep.getModId(), Collections.emptyList())
							.stream()
							.filter((c) -> dep.matches(c.getInfo().getVersion()))
							.mapToInt(candidateIntMap::get)
							.toArray();

						try {
							for (int m : matchingCandidates) {
								solver.addClause(new VecInt(new int[] { -modClauseId, -m }));
							}
						} catch (ContradictionException e) {
							throw new ModResolutionException("Found conflicting mods: " + mod.getInfo().getId() + " conflicts with " + dep, e);
						}
					}
				}

				//noinspection UnnecessaryLocalVariable
				IProblem problem = solver;
				IVecInt assumptions = new VecInt(modCandidateMap.size());

				for (String mod : modCandidateMap.keySet()) {
					int pos = assumptions.size();
					assumptions = assumptions.push(0);
					Collection<ModCandidate> candidates = modCandidateMap.get(mod);
					boolean satisfied = false;

					for (ModCandidate candidate : candidates) {
						assumptions.set(pos, candidateIntMap.get(candidate));
						if (problem.isSatisfiable(assumptions)) {
							satisfied = true;
							break;
						}
					}

					if (!satisfied) {
						if (mandatoryMods.contains(mod)) {
							throw new ModResolutionException("Could not resolve mod collection including mandatory mod '" + mod + "'");
						} else {
							assumptions = assumptions.pop();
						}
					}
				}

				// TODO: better error message, have a way to figure out precisely which mods are causing this
				if (!problem.isSatisfiable(assumptions)) {
					throw new ModResolutionException("Could not resolve mod collection due to an unknown error");
				}
				int[] model = problem.model();
				result = new HashMap<>();

				for (int i : model) {
					if (i <= 0) {
						continue;
					}

					ModCandidate candidate = intCandidateMap.get(i);
					if (result.containsKey(candidate.getInfo().getId())) {
						throw new ModResolutionException("Duplicate ID '" + candidate.getInfo().getId() + "' after solving - wrong constraints?");
					} else {
						result.put(candidate.getInfo().getId(), candidate);
					}
				}
			} catch (TimeoutException e) {
				throw new ModResolutionException("Mod collection took too long to be resolved", e);
			}
		}

		// verify result: all mandatory mods
		Set<String> missingMods = new HashSet<>();
		for (String m : mandatoryMods) {
			if (!result.keySet().contains(m)) {
				missingMods.add(m);
			}
		}

		StringBuilder errorsHard = new StringBuilder();
		StringBuilder errorsSoft = new StringBuilder();

		if (!missingMods.isEmpty()) {
			errorsHard.append("\n - Missing mods: ").append(String.join(", ", missingMods));
		} else {
			// verify result: dependencies
			for (ModCandidate candidate : result.values()) {
				for (ModDependency dependency : candidate.getInfo().getDepends()) {
					addErrorToList(logger, candidate, dependency, result, errorsHard, "requires", true);
				}

				for (ModDependency dependency : candidate.getInfo().getRecommends()) {
					addErrorToList(logger, candidate, dependency, result, errorsSoft, "recommends", true);
				}

				for (ModDependency dependency : candidate.getInfo().getBreaks()) {
					addErrorToList(logger, candidate, dependency, result, errorsHard, "is incompatible with", false);
				}

				for (ModDependency dependency : candidate.getInfo().getConflicts()) {
					addErrorToList(logger, candidate, dependency, result, errorsSoft, "conflicts with", false);
				}

				Version version = candidate.getInfo().getVersion();
				List<Version> suspiciousVersions = new ArrayList<>();

				for (ModCandidate other : modCandidateMap.get(candidate.getInfo().getId())) {
					Version otherVersion = other.getInfo().getVersion();
					if (version instanceof Comparable && otherVersion instanceof Comparable && !version.equals(otherVersion)) {
						//noinspection unchecked
						if (((Comparable) version).compareTo(otherVersion) == 0) {
							suspiciousVersions.add(otherVersion);
						}
					}
				}

				if (!suspiciousVersions.isEmpty()) {
					errorsSoft.append("\n - Conflicting versions found for ")
						.append(candidate.getInfo().getId())
						.append(": used ")
						.append(version.getFriendlyString())
						.append(", also found ")
						.append(suspiciousVersions.stream().map(Version::getFriendlyString).collect(Collectors.joining(", ")));
				}
			}
		}

		// print errors
		String errHardStr = errorsHard.toString();
		String errSoftStr = errorsSoft.toString();

		if (!errSoftStr.isEmpty()) {
			logger.warn("Warnings were found! " + errSoftStr);
		}

		if (!errHardStr.isEmpty()) {
			throw new ModResolutionException("Errors were found!" + errHardStr + errSoftStr);
		}

		return result;
	}

	private void addErrorToList(Logger logger, ModCandidate candidate, ModDependency dependency, Map<String, ModCandidate> result, StringBuilder errors, String errorType, boolean cond) {
		String depModId = dependency.getModId();

		List<String> errorList = new ArrayList<>();

		if (!isModIdValid(depModId, errorList)) {
			errors.append("\n - Mod ").append(getCandidateName(candidate)).append(" ").append(errorType).append(" ")
					.append(depModId).append(", which has an invalid mod ID because:");

			for (String error : errorList) {
				errors.append("\n\t - It ").append(error);
			}

			return;
		}

		ModCandidate depCandidate = result.get(depModId);
		// attempt searching provides
		if(depCandidate == null) {
			for (ModCandidate value : result.values()) {
				if (value.getInfo().getProvides().contains(depModId)) {
					if(FabricLoader.INSTANCE.isDevelopmentEnvironment()) logger.warn("Mod " + candidate.getInfo().getId() + " is using the provided alias " + depModId + " in place of the real mod id " + value.getInfo().getId() + ".  Please use the mod id instead of a provided alias.");
					depCandidate = value;
					break;
				}
			}
		}
		boolean isPresent = depCandidate != null && dependency.matches(depCandidate.getInfo().getVersion());

		if (isPresent != cond) {
			errors.append("\n - Mod ").append(getCandidateName(candidate)).append(" ").append(errorType).append(" ")
					.append(getDependencyVersionRequirements(dependency)).append(" of mod ")
					.append(depCandidate == null ? depModId : getCandidateName(depCandidate)).append(", ");
			if (depCandidate == null) {
				appendMissingDependencyError(errors, dependency);
			} else if (cond) {
				appendUnsatisfiedDependencyError(errors, dependency, depCandidate);
			} else if (errorType.contains("conf")) {
				// CONFLICTS WITH
				appendConflictError(errors, candidate, depCandidate, dependency);
			} else {
				appendBreakingError(errors, candidate, depCandidate, dependency);
			}
			if (depCandidate != null) {
				appendJiJInfo(errors, result, depCandidate);
			}
		}
	}

	private void appendMissingDependencyError(StringBuilder errors, ModDependency dependency) {
		errors.append("which is missing!");
		errors.append("\n\t - You must install ").append(getDependencyVersionRequirements(dependency)).append(" of ")
				.append(dependency.getModId()).append(".");

		dependency.getReason().ifPresent(reason -> {
			errors.append("\nThe following reason was provided why you must install ")
					.append(dependency.getModId())
					.append(":")
					.append("\n")
					.append(reason);
		});
	}

	private void appendUnsatisfiedDependencyError(StringBuilder errors, ModDependency dependency, ModCandidate depCandidate) {
		errors.append("but a non-matching version is present: ").append(getCandidateFriendlyVersion(depCandidate)).append("!");
		errors.append("\n\t - You must install ").append(getDependencyVersionRequirements(dependency)).append(" of ")
				.append(getCandidateName(depCandidate)).append(".");

		dependency.getReason().ifPresent(reason -> {
			errors.append("\nThe following reason was provided why you must install the required version of ")
					.append(dependency.getModId())
					.append(":\n")
					.append(reason);
		});
	}

	private void appendConflictError(StringBuilder errors, ModCandidate candidate, ModCandidate depCandidate, ModDependency dependency) {
		final String depCandidateVer = getCandidateFriendlyVersion(depCandidate);
		errors.append("but a matching version is present: ").append(depCandidateVer).append("!");
		errors.append("\n\t - While this won't prevent you from starting the game,");
		errors.append(" the developer(s) of ").append(getCandidateName(candidate));
		errors.append(" have found that version ").append(depCandidateVer).append(" of ").append(getCandidateName(depCandidate));
		errors.append(" conflicts with their mod.");
		errors.append("\n\t - It is heavily recommended to remove one of the mods.");

		dependency.getReason().ifPresent(reason -> {
			errors.append("\nThe following reason was provided why ")
					.append(depCandidateVer)
					.append(" of ")
					.append(getCandidateName(depCandidate))
					.append(" may conflict with ")
					.append(getCandidateName(candidate))
					.append(":\n")
					.append(reason);
		});
	}

	private void appendBreakingError(StringBuilder errors, ModCandidate candidate, ModCandidate depCandidate, ModDependency dependency) {
		final String depCandidateVer = getCandidateFriendlyVersion(depCandidate);
		errors.append("but a matching version is present: ").append(depCandidate.getInfo().getVersion()).append("!");
		errors.append("\n\t - The developer(s) of ").append(getCandidateName(candidate));
		errors.append(" have found that version ").append(depCandidateVer).append(" of ").append(getCandidateName(depCandidate));
		errors.append(" critically conflicts with their mod.");
		errors.append("\n\t - You must remove one of the mods.");

		dependency.getReason().ifPresent(reason -> {
			errors.append("\nThe following reason was provided why ")
					.append(depCandidateVer)
					.append(" of ")
					.append(getCandidateName(depCandidate))
					.append(" may break in the presence of ")
					.append(getCandidateName(candidate))
					.append(":\n")
					.append(reason);
		});
	}

	private void appendJiJInfo(StringBuilder errors, Map<String, ModCandidate> result, ModCandidate candidate) {
		if (candidate.getDepth() < 1) {
			errors.append("\n\t - Mod ").append(getCandidateName(candidate))
					.append(" v").append(getCandidateFriendlyVersion(candidate))
					.append(" is being loaded from the user's mod directory.");
			return;
		}
		URL originUrl = candidate.getOriginUrl();
		// step 1: try to find source mod's URL
		URL sourceUrl = null;
		try {
			for (Map.Entry<String, List<Path>> entry : inMemoryCache.entrySet()) {
				for (Path path : entry.getValue()) {
					URL url = UrlUtil.asUrl(path.normalize());
					if (originUrl.equals(url)) {
						sourceUrl = new URL(entry.getKey());
						break;
					}
				}
			}
		} catch (UrlConversionException | MalformedURLException e) {
			e.printStackTrace();
		}
		if (sourceUrl == null) {
			errors.append("\n\t - Mod ").append(getCandidateName(candidate))
					.append(" v").append(getCandidateFriendlyVersion(candidate))
					.append(" is being provided by <unknown mod>.");
			return;
		}
		// step 2: try to find source mod candidate
		ModCandidate srcCandidate = null;
		for (Map.Entry<String, ModCandidate> entry : result.entrySet()) {
			if (sourceUrl.equals(entry.getValue().getOriginUrl())) {
				srcCandidate = entry.getValue();
				break;
			}
		}
		if (srcCandidate == null) {
			errors.append("\n\t - Mod ").append(getCandidateName(candidate))
					.append(" v").append(getCandidateFriendlyVersion(candidate))
					.append(" is being provided by <unknown mod: ")
					.append(sourceUrl).append(">.");
			return;
		}
		// now we have the proper data, yay
		errors.append("\n\t - Mod ").append(getCandidateName(candidate))
				.append(" v").append(getCandidateFriendlyVersion(candidate))
				.append(" is being provided by ").append(getCandidateName(srcCandidate))
				.append(" v").append(getCandidateFriendlyVersion(candidate))
				.append('.');
	}

	private static String getCandidateName(ModCandidate candidate) {
		return "'" + candidate.getInfo().getName() + "' (" + candidate.getInfo().getId() + ")";
	}

	private static String getCandidateFriendlyVersion(ModCandidate candidate) {
		return candidate.getInfo().getVersion().getFriendlyString();
	}

	private static String getDependencyVersionRequirements(ModDependency dependency) {
		return dependency.getVersionRequirements().stream().map(predicate -> {
			String version = predicate.getVersion();
			String[] parts;
			switch(predicate.getType()) {
			case ANY:
				return "any version";
			case EQUALS:
				return "version " + version;
			case GREATER_THAN:
				return "any version after " + version;
			case LESSER_THAN:
				return "any version before " + version;
			case GREATER_THAN_OR_EQUAL:
				return "version " + version + " or later";
			case LESSER_THAN_OR_EQUAL:
				return "version " + version + " or earlier";
			case SAME_MAJOR:
				parts = version.split("\\.");

				for (int i = 1; i < parts.length; i++) {
					parts[i] = "x";
				}

				return "version " + String.join(".", parts);
			case SAME_MAJOR_AND_MINOR:
				parts = version.split("\\.");

				for (int i = 2; i < parts.length; i++) {
					parts[i] = "x";
				}

				return "version " + String.join(".", parts);
			default:
				return "unknown version"; // should be unreachable
			}
		}).collect(Collectors.joining(" or "));
	}

	/** @param errorList The list of errors. The returned list of errors all need to be prefixed with "it " in order to make sense. */
	private static boolean isModIdValid(String modId, List<String> errorList) {
		// A more useful error list for MOD_ID_PATTERN
		if (modId.isEmpty()) {
			errorList.add("is empty!");
			return false;
		}

		if (modId.length() == 1) {
			errorList.add("is only a single character! (It must be at least 2 characters long)!");
		} else if (modId.length() > 64) {
			errorList.add("has more than 64 characters!");
		}

		char first = modId.charAt(0);

		if (first < 'a' || first > 'z') {
			errorList.add("starts with an invalid character '" + first + "' (it must be a lowercase a-z - uppercase isn't allowed anywhere in the ID)");
		}

		Set<Character> invalidChars = null;

		for (int i = 1; i < modId.length(); i++) {
			char c = modId.charAt(i);

			if (c == '-' || c == '_' || ('0' <= c && c <= '9') || ('a' <= c && c <= 'z')) {
				continue;
			}

			if (invalidChars == null) {
				invalidChars = new HashSet<>();
			}

			invalidChars.add(c);
		}

		if (invalidChars != null) {
			StringBuilder error = new StringBuilder("contains invalid characters: ");
			error.append(invalidChars.stream().map(value -> "'" + value + "'").collect(Collectors.joining(", ")));
			errorList.add(error.append("!").toString());
		}

		assert errorList.isEmpty() == MOD_ID_PATTERN.matcher(modId).matches() : "Errors list " + errorList + " didn't match the mod ID pattern!";
		return errorList.isEmpty();
	}

	static class UrlProcessAction extends RecursiveAction {
		private final FabricLoader loader;
		private final Map<String, ModCandidateSet> candidatesById;
		private final URL url;
		private final int depth;
		private final boolean requiresRemap;

		UrlProcessAction(FabricLoader loader, Map<String, ModCandidateSet> candidatesById, URL url, int depth, boolean requiresRemap) {
			this.loader = loader;
			this.candidatesById = candidatesById;
			this.url = url;
			this.depth = depth;
			this.requiresRemap = requiresRemap;
		}

		@Override
		protected void compute() {
			FileSystemUtil.FileSystemDelegate jarFs;
			Path path, modJson, rootDir;
			URL normalizedUrl;

			loader.getLogger().debug("Testing " + url);

			try {
				path = UrlUtil.asPath(url).normalize();
				// normalize URL (used as key for nested JAR lookup)
				normalizedUrl = UrlUtil.asUrl(path);
			} catch (UrlConversionException e) {
				throw new RuntimeException("Failed to convert URL " + url + "!", e);
			}

			if (Files.isDirectory(path)) {
				// Directory
				modJson = path.resolve("fabric.mod.json");
				rootDir = path;

				if (loader.isDevelopmentEnvironment() && !Files.exists(modJson)) {
					loader.getLogger().warn("Adding directory " + path + " to mod classpath in development environment - workaround for Gradle splitting mods into two directories");
					synchronized (launcherSyncObject) {
						FabricLauncherBase.getLauncher().propose(url);
					}
				}
			} else {
				// JAR file
				try {
					jarFs = FileSystemUtil.getJarFileSystem(path, false);
					modJson = jarFs.get().getPath("fabric.mod.json");
					rootDir = jarFs.get().getRootDirectories().iterator().next();
				} catch (IOException e) {
					throw new RuntimeException("Failed to open mod JAR at " + path + "!");
				} catch (ZipError e) {
					throw new RuntimeException("Jar at " + path + " is corrupted, please redownload it!");
				}
			}

			LoaderModMetadata[] info;

			try {
				info = new LoaderModMetadata[] { ModMetadataParser.parseMetadata(loader.getLogger(), modJson) };
			} catch (ParseMetadataException.MissingRequired e){
				throw new RuntimeException(String.format("Mod at \"%s\" has an invalid fabric.mod.json file! The mod is missing the following required field!", path), e);
			} catch (MalformedJsonException | ParseMetadataException e) {
				throw new RuntimeException(String.format("Mod at \"%s\" has an invalid fabric.mod.json file!", path), e);
			} catch (NoSuchFileException e) {
				loader.getLogger().warn(String.format("Non-Fabric mod JAR at \"%s\", ignoring", path));
				info = new LoaderModMetadata[0];
			} catch (IOException e) {
				throw new RuntimeException(String.format("Failed to open fabric.mod.json for mod at \"%s\"!", path), e);
			} catch (Throwable t) {
				throw new RuntimeException(String.format("Failed to parse mod metadata for mod at \"%s\"", path), t);
			}

			for (LoaderModMetadata i : info) {
				ModCandidate candidate = new ModCandidate(i, normalizedUrl, depth, requiresRemap);
				boolean added;

				if (candidate.getInfo().getId() == null || candidate.getInfo().getId().isEmpty()) {
					throw new RuntimeException(String.format("Mod file `%s` has no id", candidate.getOriginUrl().getFile()));
				}

				if (!MOD_ID_PATTERN.matcher(candidate.getInfo().getId()).matches()) {
					List<String> errorList = new ArrayList<>();
					isModIdValid(candidate.getInfo().getId(), errorList);
					StringBuilder fullError = new StringBuilder("Mod id `");
					fullError.append(candidate.getInfo().getId()).append("` does not match the requirements because");

					if (errorList.size() == 1) {
						fullError.append(" it ").append(errorList.get(0));
					} else {
						fullError.append(":");
						for (String error : errorList) {
							fullError.append("\n  - It ").append(error);
						}
					}

					throw new RuntimeException(fullError.toString());
				}

				for(String provides : candidate.getInfo().getProvides()) {
					if (!MOD_ID_PATTERN.matcher(provides).matches()) {
						List<String> errorList = new ArrayList<>();
						isModIdValid(provides, errorList);
						StringBuilder fullError = new StringBuilder("Mod id provides `");
						fullError.append(provides).append("` does not match the requirements because");

						if (errorList.size() == 1) {
							fullError.append(" it ").append(errorList.get(0));
						} else {
							fullError.append(":");
							for (String error : errorList) {
								fullError.append("\n  - It ").append(error);
							}
						}

						throw new RuntimeException(fullError.toString());
					}
				}

				added = candidatesById.computeIfAbsent(candidate.getInfo().getId(), ModCandidateSet::new).add(candidate);

				if (!added) {
					loader.getLogger().debug(candidate.getOriginUrl() + " already present as " + candidate);
				} else {
					loader.getLogger().debug("Adding " + candidate.getOriginUrl() + " as " + candidate);

					List<Path> jarInJars = inMemoryCache.computeIfAbsent(candidate.getOriginUrl().toString(), (u) -> {
						loader.getLogger().debug("Searching for nested JARs in " + candidate);
						loader.getLogger().debug(u);
						Collection<NestedJarEntry> jars = candidate.getInfo().getJars();
						List<Path> list = new ArrayList<>(jars.size());

						jars.stream()
							.map((j) -> rootDir.resolve(j.getFile().replace("/", rootDir.getFileSystem().getSeparator())))
							.forEach((modPath) -> {
								if (!Files.isDirectory(modPath) && modPath.toString().endsWith(".jar")) {
									// TODO: pre-check the JAR before loading it, if possible
									loader.getLogger().debug("Found nested JAR: " + modPath);
									Path dest = inMemoryFs.getPath(UUID.randomUUID() + ".jar");

									try {
										Files.copy(modPath, dest);
									} catch (IOException e) {
										throw new RuntimeException("Failed to load nested JAR " + modPath + " into memory (" + dest + ")!", e);
									}

									list.add(dest);
								}
							});

						return list;
					});

					if (!jarInJars.isEmpty()) {
						invokeAll(
							jarInJars.stream()
								.map((p) -> {
									try {
										return new UrlProcessAction(loader, candidatesById, UrlUtil.asUrl(p.normalize()), depth + 1, requiresRemap);
									} catch (UrlConversionException e) {
										throw new RuntimeException("Failed to turn path '" + p.normalize() + "' into URL!", e);
									}
								}).collect(Collectors.toList())
						);
					}
				}
			}

			/* if (jarFs != null) {
				jarFs.close();
			} */
		}
	}

	public Map<String, ModCandidate> resolve(FabricLoader loader) throws ModResolutionException {
		ConcurrentMap<String, ModCandidateSet> candidatesById = new ConcurrentHashMap<>();

		long time1 = System.currentTimeMillis();
		Queue<UrlProcessAction> allActions = new ConcurrentLinkedQueue<>();
		ForkJoinPool pool = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
		for (ModCandidateFinder f : candidateFinders) {
			f.findCandidates(loader, (u, requiresRemap) -> {
				UrlProcessAction action = new UrlProcessAction(loader, candidatesById, u, 0, requiresRemap);
				allActions.add(action);
				pool.execute(action);
			});
		}

		// add builtin mods
		for (BuiltinMod mod : loader.getGameProvider().getBuiltinMods()) {
			addBuiltinMod(candidatesById, mod);
		}

		// Add the current Java version
		try {
			addBuiltinMod(candidatesById, new BuiltinMod(
					new File(System.getProperty("java.home")).toURI().toURL(),
					new BuiltinModMetadata.Builder("java", System.getProperty("java.specification.version").replaceFirst("^1\\.", ""))
						.setName(System.getProperty("java.vm.name"))
						.build()));
		} catch (MalformedURLException e) {
			throw new ModResolutionException("Could not add Java to the dependency constraints", e);
		}

		boolean tookTooLong = false;
		Throwable exception = null;
		try {
			pool.shutdown();
			// Comment out for debugging
			pool.awaitTermination(30, TimeUnit.SECONDS);
			for (UrlProcessAction action : allActions) {
				if (!action.isDone()) {
					tookTooLong = true;
				} else {
					Throwable t = action.getException();
					if (t != null) {
						if (exception == null) {
							exception = t;
						} else {
							exception.addSuppressed(t);
						}
					}
				}
			}
		} catch (InterruptedException e) {
			throw new ModResolutionException("Mod resolution took too long!", e);
		}
		if (tookTooLong) {
			throw new ModResolutionException("Mod resolution took too long!");
		}
		if (exception != null) {
			throw new ModResolutionException("Mod resolution failed!", exception);
		}

		long time2 = System.currentTimeMillis();
		Map<String, ModCandidate> result = findCompatibleSet(loader.getLogger(), loader.getEnvironmentType(), candidatesById);

		long time3 = System.currentTimeMillis();
		loader.getLogger().debug("Mod resolution detection time: " + (time2 - time1) + "ms");
		loader.getLogger().debug("Mod resolution time: " + (time3 - time2) + "ms");

		for (ModCandidate candidate : result.values()) {
			if (candidate.getInfo().getSchemaVersion() < ModMetadataParser.LATEST_VERSION) {
				loader.getLogger().warn("Mod ID " + candidate.getInfo().getId() + " uses outdated schema version: " + candidate.getInfo().getSchemaVersion() + " < " + ModMetadataParser.LATEST_VERSION);
			}

			candidate.getInfo().emitFormatWarnings(loader.getLogger());
		}

		return result;
	}

	private void addBuiltinMod(ConcurrentMap<String, ModCandidateSet> candidatesById, BuiltinMod mod) {
		candidatesById.computeIfAbsent(mod.metadata.getId(), ModCandidateSet::new)
				.add(new ModCandidate(new BuiltinMetadataWrapper(mod.metadata), mod.url, 0, false));
	}

	public static FileSystem getInMemoryFs() {
		return inMemoryFs;
	}
}
