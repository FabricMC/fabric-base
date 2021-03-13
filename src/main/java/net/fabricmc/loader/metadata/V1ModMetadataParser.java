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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.logging.log4j.Logger;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ContactInformation;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.Person;
import net.fabricmc.loader.lib.gson.JsonReader;
import net.fabricmc.loader.lib.gson.JsonToken;
import net.fabricmc.loader.util.version.VersionDeserializer;

final class V1ModMetadataParser {
	/**
	 * Reads a {@code fabric.mod.json} file of schema version {@code 1}.
	 *
	 * @param logger the logger to print warnings to
	 * @param reader the json reader to read the file with
	 * @return the metadata of this file, null if the file could not be parsed
	 * @throws IOException if there was any issue reading the file
	 */
	static LoaderModMetadata parse(Logger logger, JsonReader reader) throws IOException, ParseMetadataException {
		List<ParseWarning> warnings = new ArrayList<>();

		// All the values the `fabric.mod.json` may contain:
		// Required
		String id = null;
		Version version = null;

		// Optional (id provides)
		List<String> provides = new ArrayList<>();

		// Optional (mod loading)
		ModEnvironment environment = ModEnvironment.UNIVERSAL; // Default is always universal
		Map<String, List<EntrypointMetadata>> entrypoints = new HashMap<>();
		List<NestedJarEntry> jars = new ArrayList<>();
		List<V1ModMetadata.MixinEntry> mixins = new ArrayList<>();
		String accessWidener = null;

		// Optional (dependency resolution)
		Map<String, ModDependency> depends = new HashMap<>();
		Map<String, ModDependency> recommends = new HashMap<>();
		Map<String, ModDependency> suggests = new HashMap<>();
		Map<String, ModDependency> conflicts = new HashMap<>();
		Map<String, ModDependency> breaks = new HashMap<>();

		// Happy little accidents
		@Deprecated
		Map<String, ModDependency> requires = new HashMap<>();

		// Optional (metadata)
		String name = null;
		String description = null;
		List<Person> authors = new ArrayList<>();
		List<Person> contributors = new ArrayList<>();
		ContactInformation contact = null;
		List<String> license = new ArrayList<>();
		V1ModMetadata.IconEntry icon = null;

		// Optional (language adapter providers)
		Map<String, String> languageAdapters = new HashMap<>();

		// Optional (custom values)
		Map<String, CustomValue> customValues = new HashMap<>();

		while (reader.hasNext()) {
			final String key = reader.nextName();

			// Work our way from required to entirely optional
			switch (key) {
			case "schemaVersion":
				// Duplicate field, make sure it matches our current schema version
				if (reader.peek() != JsonToken.NUMBER) {
					throw new ParseMetadataException("Duplicate \"schemaVersion\" field is not a number", reader);
				}

				final int read = reader.nextInt();

				if (read != 1) {
					throw new ParseMetadataException(String.format("Duplicate \"schemaVersion\" field does not match the predicted schema version of 1. Duplicate field value is %s", read), reader);
				}

				break;
			case "id":
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Mod id must be a non-empty string with a length of 3-64 characters.", reader);
				}

				id = reader.nextString();
				break;
			case "version":
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Version must be a non-empty string", reader);
				}

				try {
					version = VersionDeserializer.deserialize(reader.nextString());
				} catch (VersionParsingException e) {
					throw new ParseMetadataException("Failed to parse version", e);
				}

				break;
			case "provides":
				readProvides(reader, provides);
				break;
			case "environment":
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Environment must be a string", reader);
				}

				environment = readEnvironment(reader);
				break;
			case "entrypoints":
				readEntrypoints(warnings, reader, entrypoints);
				break;
			case "jars":
				readNestedJarEntries(warnings, reader, jars);
				break;
			case "mixins":
				readMixinConfigs(warnings, reader, mixins);
				break;
			case "accessWidener":
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Access Widener file must be a string", reader);
				}

				accessWidener = reader.nextString();
				break;
			case "depends":
				readDependenciesContainer(reader, depends);
				break;
			case "recommends":
				readDependenciesContainer(reader, recommends);
				break;
			case "suggests":
				readDependenciesContainer(reader, suggests);
				break;
			case "conflicts":
				readDependenciesContainer(reader, conflicts);
				break;
			case "breaks":
				readDependenciesContainer(reader, breaks);
				break;
			case "requires":
				readDependenciesContainer(reader, requires);
				break;
			case "name":
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Mod name must be a string", reader);
				}

				name = reader.nextString();
				break;
			case "description":
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Mod description must be a string", reader);
				}

				description = reader.nextString();
				break;
			case "authors":
				readPeople(warnings, reader, authors);
				break;
			case "contributors":
				readPeople(warnings, reader, contributors);
				break;
			case "contact":
				contact = readContactInfo(reader);
				break;
			case "license":
				readLicense(reader, license);
				break;
			case "icon":
				icon = readIcon(reader);
				break;
			case "languageAdapters":
				readLanguageAdapters(reader, languageAdapters);
				break;
			case "custom":
				readCustomValues(reader, customValues);
				break;
			default:
				warnings.add(new ParseWarning(reader.getLineNumber(), reader.getColumn(), key, "Unsupported root entry"));
				reader.skipValue();
				break;
			}
		}

		// Validate all required fields are resolved
		if (id == null) {
			throw new ParseMetadataException.MissingRequired("id");
		}

		if (version == null) {
			throw new ParseMetadataException.MissingRequired("version");
		}

		ModMetadataParser.logWarningMessages(logger, id, warnings);

		return new V1ModMetadata(id, version, provides, environment, entrypoints, jars, mixins, accessWidener, depends, recommends, suggests, conflicts, breaks, requires, name, description, authors, contributors, contact, license, icon, languageAdapters, customValues);
	}

	private static void readProvides(JsonReader reader, List<String> provides) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_ARRAY) {
			throw new ParseMetadataException("Provides must be an array");
		}

		reader.beginArray();

		while (reader.hasNext()) {
			if (reader.peek() != JsonToken.STRING) {
				throw new ParseMetadataException("Provided id must be a string", reader);
			}

			provides.add(reader.nextString());
		}

		reader.endArray();
	}

	private static ModEnvironment readEnvironment(JsonReader reader) throws ParseMetadataException, IOException {
		final String environment = reader.nextString().toLowerCase(Locale.ROOT);

		if (environment.isEmpty() || environment.equals("*")) {
			return ModEnvironment.UNIVERSAL;
		} else if (environment.equals("client")) {
			return ModEnvironment.CLIENT;
		} else if (environment.equals("server")) {
			return ModEnvironment.SERVER;
		} else {
			throw new ParseMetadataException("Invalid environment type: " + environment + "!", reader);
		}
	}

	private static void readEntrypoints(List<ParseWarning> warnings, JsonReader reader, Map<String, List<EntrypointMetadata>> entrypoints) throws IOException, ParseMetadataException {
		// Entrypoints must be an object
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseMetadataException("Entrypoints must be an object", reader);
		}

		reader.beginObject();

		while (reader.hasNext()) {
			final String key = reader.nextName();

			List<EntrypointMetadata> metadata = new ArrayList<>();

			if (reader.peek() != JsonToken.BEGIN_ARRAY) {
				throw new ParseMetadataException("Entrypoint list must be an array!", reader);
			}

			reader.beginArray();

			while (reader.hasNext()) {
				String adapter = "default";
				String value = null;

				// Entrypoints may be specified directly as a string or as an object to allow specification of the language adapter to use.
				switch (reader.peek()) {
				case STRING:
					value = reader.nextString();
					break;
				case BEGIN_OBJECT:
					reader.beginObject();

					while (reader.hasNext()) {
						final String entryKey = reader.nextName();
						switch (entryKey) {
						case "adapter":
							adapter = reader.nextString();
							break;
						case "value":
							value = reader.nextString();
							break;
						default:
							warnings.add(new ParseWarning(reader.getLineNumber(), reader.getColumn(), entryKey, "Invalid entry in entrypoint metadata"));
							reader.skipValue();
							break;
						}
					}

					reader.endObject();
					break;
				default:
					throw new ParseMetadataException("Entrypoint must be a string or object with \"value\" field", reader);
				}

				if (value == null) {
					throw new ParseMetadataException.MissingRequired("Entrypoint value must be present");
				}

				metadata.add(new V1ModMetadata.EntrypointMetadataImpl(adapter, value));
			}

			reader.endArray();

			// Empty arrays are acceptable, do not check if the List of metadata is empty
			entrypoints.put(key, metadata);
		}

		reader.endObject();
	}

	private static void readNestedJarEntries(List<ParseWarning> warnings, JsonReader reader, List<NestedJarEntry> jars) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_ARRAY) {
			throw new ParseMetadataException("Jar entries must be in an array", reader);
		}

		reader.beginArray();

		while (reader.hasNext()) {
			if (reader.peek() != JsonToken.BEGIN_OBJECT) {
				throw new ParseMetadataException("Invalid type for JAR entry!", reader);
			}

			reader.beginObject();
			String file = null;

			while (reader.hasNext()) {
				final String key = reader.nextName();

				if (key.equals("file")) {
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseMetadataException("\"file\" entry in jar object must be a string", reader);
					}

					file = reader.nextString();
				} else {
					warnings.add(new ParseWarning(reader.getLineNumber(), reader.getColumn(), key, "Invalid entry in jar entry"));
					reader.skipValue();
				}
			}

			reader.endObject();

			if (file == null) {
				throw new ParseMetadataException("Missing mandatory key 'file' in JAR entry!", reader);
			}

			jars.add(new V1ModMetadata.JarEntry(file));
		}

		reader.endArray();
	}

	private static void readMixinConfigs(List<ParseWarning> warnings, JsonReader reader, List<V1ModMetadata.MixinEntry> mixins) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_ARRAY) {
			throw new ParseMetadataException("Mixin configs must be in an array", reader);
		}

		reader.beginArray();

		while (reader.hasNext()) {
			switch (reader.peek()) {
			case STRING:
				// All mixin configs specified via string are assumed to be universal
				mixins.add(new V1ModMetadata.MixinEntry(reader.nextString(), ModEnvironment.UNIVERSAL));
				break;
			case BEGIN_OBJECT:
				reader.beginObject();

				String config = null;
				ModEnvironment environment = null;

				while (reader.hasNext()) {
					final String key = reader.nextName();

					switch (key) {
					// Environment is optional
					case "environment":
						environment = V1ModMetadataParser.readEnvironment(reader);
						break;
					case "config":
						if (reader.peek() != JsonToken.STRING) {
							throw new ParseMetadataException("Value of \"config\" must be a string", reader);
						}

						config = reader.nextString();
						break;
					default:
						warnings.add(new ParseWarning(reader.getLineNumber(), reader.getColumn(), key, "Invalid entry in mixin config entry"));
						reader.skipValue();
					}
				}

				reader.endObject();

				if (environment == null) {
					environment = ModEnvironment.UNIVERSAL; // Default to universal
				}

				if (config == null) {
					throw new ParseMetadataException.MissingRequired("Missing mandatory key 'config' in mixin entry!");
				}

				mixins.add(new V1ModMetadata.MixinEntry(config, environment));
				break;
			default:
				warnings.add(new ParseWarning(reader.getLineNumber(), reader.getColumn(), "Invalid mixin entry type"));
				reader.skipValue();
				break;
			}
		}

		reader.endArray();
	}

	static void readDependenciesContainer(JsonReader reader, Map<String, ModDependency> modDependencies) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseMetadataException("Dependency container must be an object!", reader);
		}

		reader.beginObject();

		while (reader.hasNext()) {
			final String modId = reader.nextName();
			final List<String> matcherStringList = new ArrayList<>();
			/* @Nullable */
			String reason = null;
			ModEnvironment environment = ModEnvironment.UNIVERSAL;

			switch (reader.peek()) {
			case STRING:
				matcherStringList.add(reader.nextString());
				break;
			case BEGIN_ARRAY:
				reader.beginArray();

				while (reader.hasNext()) {
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseMetadataException("Dependency version range array must only contain string values", reader);
					}

					matcherStringList.add(reader.nextString());
				}

				reader.endArray();
				break;
			case BEGIN_OBJECT:
				reader.beginObject();

				while (reader.hasNext()) {
					final String key = reader.nextName();

					switch (key) {
					case "versions":
						switch (reader.peek()) {
						case STRING: // Single version predicate
							matcherStringList.add(reader.nextString());
							break;
						case BEGIN_ARRAY: // Multiple version predicates
							reader.beginArray();

							while (reader.hasNext()) {
								if (reader.peek() != JsonToken.STRING) {
									throw new ParseMetadataException(String.format("Entry in version predicate array for %s must only contain strings", modId));
								}

								matcherStringList.add(reader.nextString());
							}

							reader.endArray();
							break;
						default:
							throw new ParseMetadataException(String.format("Versions value in dependency for %s must be an array of versions or a single version as a string", modId), reader);
						}

						break;
					case "reason":
						if (reader.peek() != JsonToken.STRING) {
							throw new ParseMetadataException(String.format("Reason value in dependency for %s must be a string", modId), reader);
						}

						reason = reader.nextString();

						break;
					case "environment":
						if (reader.peek() != JsonToken.STRING) {
							throw new ParseMetadataException(String.format("Environment in dependency for %s must be a string", modId), reader);
						}

						environment = readEnvironment(reader);
					default:
						throw new ParseMetadataException(String.format("Invalid field %s in dependency for %s", key, modId), reader);
					}
				}

				reader.endObject();
				break;
			default:
				throw new ParseMetadataException("Dependency version range must be a string or string array!", reader);
			}

			modDependencies.put(modId, new ModDependencyImpl(modId, environment, reason, matcherStringList));
		}

		reader.endObject();
	}

	private static void readPeople(List<ParseWarning> warnings, JsonReader reader, List<Person> people) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_ARRAY) {
			throw new ParseMetadataException("List of people must be an array", reader);
		}

		reader.beginArray();

		while (reader.hasNext()) {
			switch (reader.peek()) {
			case STRING:
				// Just a name
				people.add(new SimplePerson(reader.nextString()));
				break;
			case BEGIN_OBJECT:
				// Map-backed impl
				reader.beginObject();
				// Name is required
				String personName = null;
				ContactInformation contactInformation = null;

				while (reader.hasNext()) {
					final String key = reader.nextName();

					switch (key) {
					case "name":
						if (reader.peek() != JsonToken.STRING) {
							throw new ParseMetadataException("Name of person in dependency container must be a string", reader);
						}

						personName = reader.nextString();
						break;
					// Effectively optional
					case "contact":
						contactInformation = V1ModMetadataParser.readContactInfo(reader);
						break;
					default:
						// Ignore unsupported keys
						warnings.add(new ParseWarning(reader.getLineNumber(), reader.getColumn(), key, "Invalid entry in person"));
						reader.skipValue();
					}
				}

				reader.endObject();

				if (personName == null) {
					throw new ParseMetadataException.MissingRequired("Person object must have a 'name' field!");
				}

				if (contactInformation == null) {
					contactInformation = ContactInformation.EMPTY; // Empty if not specified
				}

				people.add(new ContactInfoBackedPerson(personName, contactInformation));
				break;
			default:
				throw new ParseMetadataException("Person type must be an object or string!", reader);
			}
		}

		reader.endArray();
	}

	private static ContactInformation readContactInfo(JsonReader reader) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseMetadataException("Contact info must in an object", reader);
		}

		reader.beginObject();

		final Map<String, String> map = new HashMap<>();

		while (reader.hasNext()) {
			final String key = reader.nextName();

			if (reader.peek() != JsonToken.STRING) {
				throw new ParseMetadataException("Contact information entries must be a string", reader);
			}

			map.put(key, reader.nextString());
		}

		reader.endObject();

		// Map is wrapped as unmodifiable in the contact info impl
		return new MapBackedContactInformation(map);
	}

	private static void readLicense(JsonReader reader, List<String> license) throws IOException, ParseMetadataException {
		switch (reader.peek()) {
		case STRING:
			license.add(reader.nextString());
			break;
		case BEGIN_ARRAY:
			reader.beginArray();

			while (reader.hasNext()) {
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("List of licenses must only contain strings", reader);
				}

				license.add(reader.nextString());
			}

			reader.endArray();
			break;
		default:
			throw new ParseMetadataException("License must be a string or array of strings!", reader);
		}
	}

	private static V1ModMetadata.IconEntry readIcon(JsonReader reader) throws IOException, ParseMetadataException {
		switch (reader.peek()) {
		case STRING:
			return new V1ModMetadata.Single(reader.nextString());
		case BEGIN_OBJECT:
			reader.beginObject();

			final SortedMap<Integer, String> iconMap = new TreeMap<>(Comparator.naturalOrder());

			while (reader.hasNext()) {
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Icon path must be a string", reader);
				}

				String key = reader.nextName();

				int size;

				try {
					size = Integer.parseInt(key);
				} catch (NumberFormatException e) {
					throw new ParseMetadataException("Could not parse icon size '" + key + "'!", e);
				}

				if (size < 1) {
					throw new ParseMetadataException("Size must be positive!", reader);
				}
			}

			reader.endObject();

			if (iconMap.isEmpty()) {
				throw new ParseMetadataException("Icon object must not be empty!", reader);
			}

			return new V1ModMetadata.MapEntry(iconMap);
		default:
			throw new ParseMetadataException("Icon entry must be an object or string!", reader);
		}
	}

	private static void readLanguageAdapters(JsonReader reader, Map<String, String> languageAdapters) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseMetadataException("Language adapters must be in an object", reader);
		}

		reader.beginObject();

		while (reader.hasNext()) {
			final String adapter = reader.nextName();

			if (reader.peek() != JsonToken.STRING) {
				throw new ParseMetadataException("Value of language adapter entry must be a string", reader);
			}

			languageAdapters.put(adapter, reader.nextString());
		}

		reader.endObject();
	}

	private static void readCustomValues(JsonReader reader, Map<String, CustomValue> customValues) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseMetadataException("Custom values must be in an object!", reader);
		}

		reader.beginObject();

		while (reader.hasNext()) {
			customValues.put(reader.nextName(), CustomValueImpl.readCustomValue(reader));
		}

		reader.endObject();
	}

	private V1ModMetadataParser() {
	}
}
