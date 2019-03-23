package net.fabricmc.api.settings;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;

public abstract class Settings<S> {

    private String name;
    private HashMap<String, Settings> subSettingsHashMap = new HashMap<>();
    private HashMap<String, Setting> settingHashMap = new HashMap<>();
    private HashMap<String, Object> cachedValueMap = new HashMap<>();

	public Settings(String name) {
        this.name = name;
    }

	/**
	 * Creates a new settings object with no name. This should be used only for root settings.
	 */
	public Settings() {
        this(null);
    }

	/**
	 * Creates a new {@link SettingBuilder} with type {@link Object}
	 * @return the created {@link SettingBuilder}
	 */
	public SettingBuilder<S, Object> builder() {
        return builder(Object.class);
    }

	/**
	 * Creates a new {@link SettingBuilder}
	 * @param clazz	The class of type of the to-be created {@link SettingBuilder}
	 * @param <T>	The class of type of the to-be created {@link SettingBuilder}
	 * @return		The created {@link SettingBuilder}
	 */
	public <T> SettingBuilder<S, T> builder(Class<T> clazz) {
        return new SettingBuilder<>(this, clazz);
    }

	/**
	 * Creates a new {@link Settings} object and stores it in this objects subsettings map
	 * @param name	The name of the new {@link Settings} object
	 * @return		The created {@link Settings} object
	 */
	public Settings sub(String name) {
        if (!subSettingsHashMap.containsKey(name)) {
            subSettingsHashMap.put(name, createSub(name));
        }
        return subSettingsHashMap.get(name);
    }

	/**
	 * Finds the setting by the given name, and sets its value. If no setting is found, the value is cached for when said setting is registered.
	 * @param name	The name of the setting
	 * @param value	The new value of the setting
	 */
	public void set(String name, Object value) {
        if (hasSetting(name)) {
            if (attemptSet(name, value)) return;
        }
        cachedValueMap.put(name, value);
    }

	/**
	 * @param name 	The name of the setting
	 * @return		Whether or not this {@link Settings} object has a registered setting by name <code>name</code>.
	 */
	public boolean hasSetting(String name) {
        return settingHashMap.containsKey(name);
    }

	/**
	 * @param name	The name of the setting
	 * @return		The setting by name <code>name</code> or <code>null</code> if none was found.
	 */
	public Setting getSetting(String name) {
        return settingHashMap.get(name);
    }

	/**
	 * Registers a setting and sets its value if there was a value cached for its name.
	 */
	<T> void registerAndRecover(Setting<T> setting) {
        String name = setting.getName();
        settingHashMap.put(name, setting);
        if (cachedValueMap.containsKey(name)) {
            attemptSet(name, cachedValueMap.get(name));
        }
    }

    private boolean attemptSet(String name, Object value) {
        if (!getSetting(name).getType().isAssignableFrom(value.getClass())) return false;
        getSetting(name).setValue(value);
        return true;
    }

	/**
	 * Creates a new {@link Settings} object
	 * @param name the name for this settings object
	 * @return A new {@link Settings} object
	 */
	protected abstract Settings<S> createSub(String name);

	/**
	 * Writes this {@link Settings} object to the given {@link InputStream}
	 * @param stream the stream to write to
	 */
	public abstract void serialise(InputStream stream);

	/**
	 * Reads from the given {@link OutputStream} and mutatates this {@link Settings}.
	 * @param stream the stream to write from
	 */
    public abstract void deserialise(OutputStream stream);

	/**
	 * @return This {@link Settings}' name
	 */
	public String getName() {
        return name;
    }

}