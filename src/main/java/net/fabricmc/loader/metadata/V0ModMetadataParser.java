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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Logger;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ContactInformation;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.Person;
import net.fabricmc.loader.lib.gson.JsonReader;
import net.fabricmc.loader.lib.gson.JsonToken;
import net.fabricmc.loader.util.version.VersionDeserializer;

final class V0ModMetadataParser {
	private static final Pattern WEBSITE_PATTERN = Pattern.compile("\\((.+)\\)");
	private static final Pattern EMAIL_PATTERN = Pattern.compile("<(.+)>");

	public static LoaderModMetadata parse(Logger logger, JsonReader reader) throws IOException, ParseMetadataException {
		List<ParseWarning> warnings = new ArrayList<>();

		// All the values the `fabric.mod.json` may contain:
		// Required
		String id = null;
		Version version = null;

		// Optional (mod loading)
		Map<String, ModDependency> requires = new HashMap<>();
		Map<String, ModDependency> conflicts = new HashMap<>();
		V0ModMetadata.Mixins mixins = null;
		ModEnvironment environment = ModEnvironment.UNIVERSAL; // Default is always universal
		String initializer = null;
		List<String> initializers = new ArrayList<>();

		String name = null;
		String description = null;
		Map<String, ModDependency> recommends = new HashMap<>();
		List<Person> authors = new ArrayList<>();
		List<Person> contributors = new ArrayList<>();
		ContactInformation links = null;
		String license = null;

		while (reader.hasNext()) {
			final String key = reader.nextName();

			switch (key) {
			case "schemaVersion":
				// Duplicate field, make sure it matches our current schema version
				if (reader.peek() != JsonToken.NUMBER) {
					throw new ParseMetadataException("Duplicate \"schemaVersion\" field is not a number", reader);
				}

				final int read = reader.nextInt();

				if (read != 0) {
					throw new ParseMetadataException(String.format("Duplicate \"schemaVersion\" field does not match the predicted schema version of 0. Duplicate field value is %s", read), reader);
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

				final String rawVersion = reader.nextString();

				try {
					version = VersionDeserializer.deserialize(rawVersion);
				} catch (VersionParsingException e) {
					throw new ParseMetadataException(String.format("Failed to parse version: %s", rawVersion), e);
				}

				break;
			case "requires":
				readDependenciesContainer(reader, requires, "requires");
				break;
			case "conflicts":
				readDependenciesContainer(reader, conflicts, "conflicts");
				break;
			case "mixins":
				mixins = readMixins(warnings, reader);
				break;
			case "side":
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Side must be a string", reader);
				}

				final String rawEnvironment = reader.nextString();

				switch (rawEnvironment) {
				case "universal":
					environment = ModEnvironment.UNIVERSAL;
					break;
				case "client":
					environment = ModEnvironment.CLIENT;
					break;
				case "server":
					environment = ModEnvironment.SERVER;
					break;
				default:
					warnings.add(new ParseWarning(reader.getLineNumber(), reader.getColumn(), rawEnvironment, "Invalid side type"));
				}

				break;
			case "initializer":
				// `initializer` and `initializers` cannot be used at the same time
				if (!initializers.isEmpty()) {
					throw new ParseMetadataException("initializer and initializers should not be set at the same time! (mod ID '" + id + "')");
				}

				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Initializer must be a non-empty string", reader);
				}

				initializer = reader.nextString();
				break;
			case "initializers":
				// `initializer` and `initializers` cannot be used at the same time
				if (initializer != null) {
					throw new ParseMetadataException("initializer and initializers should not be set at the same time! (mod ID '" + id + "')");
				}

				if (reader.peek() != JsonToken.BEGIN_ARRAY) {
					throw new ParseMetadataException("Initializers must be in a list", reader);
				}

				reader.beginArray();

				while (reader.hasNext()) {
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseMetadataException("Initializer in initializers list must be a string", reader);
					}

					initializers.add(reader.nextString());
				}

				reader.endArray();

				break;
			case "name":
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Name must be a string", reader);
				}

				name = reader.nextString();
				break;
			case "description":
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Mod description must be a string", reader);
				}

				description = reader.nextString();
				break;
			case "recommends":
				readDependenciesContainer(reader, recommends, "recommends");
				break;
			case "authors":
				readPeople(warnings, reader, authors);
				break;
			case "contributors":
				readPeople(warnings, reader, contributors);
				break;
			case "links":
				links = readLinks(warnings, reader);
				break;
			case "license":
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("License name must be a string", reader);
				}

				license = reader.nextString();
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

		// Optional stuff
		if (links == null) {
			links = ContactInformation.EMPTY;
		}

		return new V0ModMetadata(id, version, requires, conflicts, mixins, environment, initializer, initializers, name, description, recommends, authors, contributors, links, license);
	}

	private static ContactInformation readLinks(List<ParseWarning> warnings, JsonReader reader) throws IOException, ParseMetadataException {
		final Map<String, String> contactInfo = new HashMap<>();

		switch (reader.peek()) {
		case STRING:
			contactInfo.put("homepage", reader.nextString());
			break;
		case BEGIN_OBJECT:
			reader.beginObject();

			while (reader.hasNext()) {
				final String key = reader.nextName();

				switch (key) {
				case "homepage":
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseMetadataException("homepage link must be a string", reader);
					}

					contactInfo.put("homepage", reader.nextString());
					break;
				case "issues":
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseMetadataException("issues link must be a string", reader);
					}

					contactInfo.put("issues", reader.nextString());
					break;
				case "sources":
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseMetadataException("sources link must be a string", reader);
					}

					contactInfo.put("sources", reader.nextString());
					break;
				default:
					warnings.add(new ParseWarning(reader.getLineNumber(), reader.getColumn(), key, "Unsupported links entry"));
					reader.skipValue();
				}
			}

			reader.endObject();
			break;
		default:
			throw new ParseMetadataException("Expected links to be an object or string", reader);
		}

		return new MapBackedContactInformation(contactInfo);
	}

	private static V0ModMetadata.Mixins readMixins(List<ParseWarning> warnings, JsonReader reader) throws IOException, ParseMetadataException {
		final List<String> client = new ArrayList<>();
		final List<String> common = new ArrayList<>();
		final List<String> server = new ArrayList<>();

		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseMetadataException("Expected mixins to be an object.", reader);
		}

		reader.beginObject();

		while (reader.hasNext()) {
			final String environment = reader.nextName();

			switch (environment) {
			case "client":
				client.addAll(readStringArray(reader, "client"));
				break;
			case "common":
				common.addAll(readStringArray(reader, "common"));
				break;
			case "server":
				server.addAll(readStringArray(reader, "server"));
				break;
			default:
				warnings.add(new ParseWarning(reader.getLineNumber(), reader.getColumn(), environment, "Invalid environment type"));
				reader.skipValue();
			}
		}

		reader.endObject();
		return new V0ModMetadata.Mixins(client, common, server);
	}

	private static List<String> readStringArray(JsonReader reader, String key) throws IOException, ParseMetadataException {
		switch (reader.peek()) {
		case NULL:
			reader.nextNull();
			return Collections.emptyList();
		case STRING:
			return Collections.singletonList(reader.nextString());
		case BEGIN_ARRAY:
			reader.beginArray();
			final List<String> list = new ArrayList<>();

			while (reader.hasNext()) {
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException(String.format("Expected entries in %s to be an array of strings", key), reader);
				}

				list.add(reader.nextString());
			}

			reader.endArray();
			return list;
		default:
			throw new ParseMetadataException(String.format("Expected %s to be a string or an array of strings", key), reader);
		}
	}

	private static void readDependenciesContainer(JsonReader reader, Map<String, ModDependency> dependencies, String name) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseMetadataException(String.format("%s must be an object containing dependencies.", name), reader);
		}

		reader.beginObject();

		while (reader.hasNext()) {
			final String modId = reader.nextName();
			final List<String> versionMatchers = new ArrayList<>();

			switch (reader.peek()) {
			case STRING:
				versionMatchers.add(reader.nextString());
				break;
			case BEGIN_ARRAY:
				reader.beginArray();

				while (reader.hasNext()) {
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseMetadataException("List of version requirements must be strings", reader);
					}

					versionMatchers.add(reader.nextString());
				}

				reader.endArray();
				break;
			default:
				throw new ParseMetadataException("Expected version to be a string or array", reader);
			}

			// V0 does not support environment dependencies or dependency reasons
			dependencies.put(modId, new ModDependencyImpl(modId, ModEnvironment.UNIVERSAL, null, versionMatchers));
		}

		reader.endObject();
	}

	private static void readPeople(List<ParseWarning> warnings, JsonReader reader, List<Person> people) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_ARRAY) {
			throw new ParseMetadataException("List of people must be an array", reader);
		}

		reader.beginArray();

		while (reader.hasNext()) {
			people.add(readPerson(warnings, reader));
		}

		reader.endArray();
	}

	private static Person readPerson(List<ParseWarning> warnings, JsonReader reader) throws IOException, ParseMetadataException {
		final HashMap<String, String> contactMap = new HashMap<>();
		String name = "";

		switch (reader.peek()) {
		case STRING:
			final String person = reader.nextString();
			String[] parts = person.split(" ");

			Matcher websiteMatcher = V0ModMetadataParser.WEBSITE_PATTERN.matcher(parts[parts.length - 1]);

			if (websiteMatcher.matches()) {
				contactMap.put("website", websiteMatcher.group(1));
				parts = Arrays.copyOf(parts, parts.length - 1);
			}

			Matcher emailMatcher = V0ModMetadataParser.EMAIL_PATTERN.matcher(parts[parts.length - 1]);

			if (emailMatcher.matches()) {
				contactMap.put("email", emailMatcher.group(1));
				parts = Arrays.copyOf(parts, parts.length - 1);
			}

			name = String.join(" ", parts);

			return new ContactInfoBackedPerson(name, new MapBackedContactInformation(contactMap));
		case BEGIN_OBJECT:
			reader.beginObject();

			while (reader.hasNext()) {
				final String key = reader.nextName();

				switch (key) {
				case "name":
					if (reader.peek() != JsonToken.STRING) {
						break;
					}

					name = reader.nextString();
					break;
				case "email":
					if (reader.peek() != JsonToken.STRING) {
						break;
					}

					contactMap.put("email", reader.nextString());
					break;
				case "website":
					if (reader.peek() != JsonToken.STRING) {
						break;
					}

					contactMap.put("website", reader.nextString());
					break;
				default:
					warnings.add(new ParseWarning(reader.getLineNumber(), reader.getColumn(), key, "Unsupported contact information entry"));
					reader.skipValue();
				}
			}

			reader.endObject();
			return new ContactInfoBackedPerson(name, new MapBackedContactInformation(contactMap));
		default:
			throw new ParseMetadataException("Expected person to be a string or object", reader);
		}
	}
}
