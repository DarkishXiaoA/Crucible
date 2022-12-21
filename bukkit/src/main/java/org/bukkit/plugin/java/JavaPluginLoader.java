package org.bukkit.plugin.java;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableList;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.regex.Pattern;
import net.md_5.specialsource.InheritanceMap;
import net.md_5.specialsource.JarMapping;
import net.md_5.specialsource.transformer.MappingTransformer;
import net.md_5.specialsource.transformer.MavenShade;
import org.apache.commons.lang.Validate;
import org.bukkit.Server;
import org.bukkit.Warning;
import org.bukkit.Warning.WarningState;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.AuthorNagException;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.UnknownDependencyException;
import org.bukkit.plugin.java.JavaPluginLoader.1;
import org.spigotmc.CustomTimingsHandler;
import org.yaml.snakeyaml.error.YAMLException;

public final class JavaPluginLoader implements PluginLoader {
    final Server server;
    private final Pattern[] fileFilters = new Pattern[]{Pattern.compile("\\.jar$")};
    private final Map<String, Class<?>> classes = new HashMap();
    private final Map<String, PluginClassLoader> loaders = new LinkedHashMap();
    public static final CustomTimingsHandler pluginParentTimer = new CustomTimingsHandler("** Plugins");
    private InheritanceMap globalInheritanceMap = null;

    /** @deprecated */
    @Deprecated
    public JavaPluginLoader(Server instance) {
        Validate.notNull(instance, "Server cannot be null");
        this.server = instance;
    }

    public Plugin loadPlugin(File file) throws InvalidPluginException {
        Validate.notNull(file, "File cannot be null");
        if (!file.exists()) {
            throw new InvalidPluginException(new FileNotFoundException(file.getPath() + " does not exist"));
        } else {
            PluginDescriptionFile description;
            try {
                description = this.getPluginDescription(file);
            } catch (InvalidDescriptionException var11) {
                throw new InvalidPluginException(var11);
            }

            File dataFolder = new File(file.getParentFile(), description.getName());
            File oldDataFolder = this.getDataFolder(file);
            if (!dataFolder.equals(oldDataFolder)) {
                if (dataFolder.isDirectory() && oldDataFolder.isDirectory()) {
                    this.server.getLogger().log(Level.INFO, String.format("While loading %s (%s) found old-data folder: %s next to the new one: %s", description.getName(), file, oldDataFolder, dataFolder));
                } else if (oldDataFolder.isDirectory() && !dataFolder.exists()) {
                    if (!oldDataFolder.renameTo(dataFolder)) {
                        throw new InvalidPluginException("Unable to rename old data folder: '" + oldDataFolder + "' to: '" + dataFolder + "'");
                    }

                    this.server.getLogger().log(Level.INFO, String.format("While loading %s (%s) renamed data folder: '%s' to '%s'", description.getName(), file, oldDataFolder, dataFolder));
                }
            }

            if (dataFolder.exists() && !dataFolder.isDirectory()) {
                throw new InvalidPluginException(String.format("Projected datafolder: '%s' for %s (%s) exists and is not a directory", dataFolder, description.getName(), file));
            } else {
                List<String> depend = description.getDepend();
                if (depend == null) {
                    depend = ImmutableList.of();
                }

                Iterator var6 = ((List)depend).iterator();

                String pluginName;
                PluginClassLoader current;
                do {
                    if (!var6.hasNext()) {
                        PluginClassLoader loader;
                        try {
                            loader = new PluginClassLoader(this, this.getClass().getClassLoader(), description, dataFolder, file);
                        } catch (InvalidPluginException var9) {
                            throw var9;
                        } catch (Throwable var10) {
                            throw new InvalidPluginException(var10);
                        }

                        this.loaders.put(description.getName(), loader);
                        return loader.plugin;
                    }

                    pluginName = (String)var6.next();
                    if (this.loaders == null) {
                        throw new UnknownDependencyException(pluginName);
                    }

                    current = (PluginClassLoader)this.loaders.get(pluginName);
                } while(current != null);

                throw new UnknownDependencyException(pluginName);
            }
        }
    }

    private File getDataFolder(File file) {
        File dataFolder = null;
        String filename = file.getName();
        int index = file.getName().lastIndexOf(".");
        if (index != -1) {
            String name = filename.substring(0, index);
            dataFolder = new File(file.getParentFile(), name);
        } else {
            dataFolder = new File(file.getParentFile(), filename + "_");
        }

        return dataFolder;
    }

    public PluginDescriptionFile getPluginDescription(File file) throws InvalidDescriptionException {
        Validate.notNull(file, "File cannot be null");
        JarFile jar = null;
        InputStream stream = null;

        PluginDescriptionFile var5;
        try {
            jar = new JarFile(file);
            JarEntry entry = jar.getJarEntry("plugin.yml");
            if (entry == null) {
                throw new InvalidDescriptionException(new FileNotFoundException("Jar does not contain plugin.yml"));
            }

            stream = jar.getInputStream(entry);
            var5 = new PluginDescriptionFile(stream);
        } catch (IOException var18) {
            throw new InvalidDescriptionException(var18);
        } catch (YAMLException var19) {
            throw new InvalidDescriptionException(var19);
        } finally {
            if (jar != null) {
                try {
                    jar.close();
                } catch (IOException var17) {
                }
            }

            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException var16) {
                }
            }

        }

        return var5;
    }

    public Pattern[] getPluginFileFilters() {
        return (Pattern[])this.fileFilters.clone();
    }

    Class<?> getClassByName(String name) {
        Class<?> cachedClass = (Class)this.classes.get(name);
        if (cachedClass != null) {
            return cachedClass;
        } else {
            Iterator var3 = this.loaders.keySet().iterator();

            while(var3.hasNext()) {
                String current = (String)var3.next();
                PluginClassLoader loader = (PluginClassLoader)this.loaders.get(current);

                try {
                    cachedClass = loader.findClass(name, false);
                } catch (ClassNotFoundException var7) {
                }

                if (cachedClass != null) {
                    return cachedClass;
                }
            }

            return null;
        }
    }

    void setClass(String name, Class<?> clazz) {
        if (!this.classes.containsKey(name)) {
            this.classes.put(name, clazz);
            if (ConfigurationSerializable.class.isAssignableFrom(clazz)) {
                Class<? extends ConfigurationSerializable> serializable = clazz.asSubclass(ConfigurationSerializable.class);
                ConfigurationSerialization.registerClass(serializable);
            }
        }

    }

    private void removeClass(String name) {
        Class clazz = (Class)this.classes.remove(name);

        try {
            if (clazz != null && ConfigurationSerializable.class.isAssignableFrom(clazz)) {
                Class<? extends ConfigurationSerializable> serializable = clazz.asSubclass(ConfigurationSerializable.class);
                ConfigurationSerialization.unregisterClass(serializable);
            }
        } catch (NullPointerException var4) {
        }

    }

    public Map<Class<? extends Event>, Set<RegisteredListener>> createRegisteredListeners(Listener listener, Plugin plugin) {
        Validate.notNull(plugin, "Plugin can not be null");
        Validate.notNull(listener, "Listener can not be null");
        boolean useTimings = this.server.getPluginManager().useTimings();
        HashMap ret = new HashMap();

        HashSet methods;
        try {
            Method[] publicMethods = listener.getClass().getMethods();
            methods = new HashSet(publicMethods.length, 1.0F);
            Method[] var7 = publicMethods;
            int var8 = publicMethods.length;
            int var9 = 0;

            label81:
            while(true) {
                Method method;
                if (var9 >= var8) {
                    var7 = listener.getClass().getDeclaredMethods();
                    var8 = var7.length;
                    var9 = 0;

                    while(true) {
                        if (var9 >= var8) {
                            break label81;
                        }

                        method = var7[var9];
                        methods.add(method);
                        ++var9;
                    }
                }

                method = var7[var9];
                methods.add(method);
                ++var9;
            }
        } catch (NoClassDefFoundError var15) {
            plugin.getLogger().severe("Plugin " + plugin.getDescription().getFullName() + " has failed to register events for " + listener.getClass() + " because " + var15.getMessage() + " does not exist.");
            return ret;
        }

        Iterator var16 = methods.iterator();

        while(true) {
            while(true) {
                Method method;
                EventHandler eh;
                do {
                    if (!var16.hasNext()) {
                        return ret;
                    }

                    method = (Method)var16.next();
                    eh = (EventHandler)method.getAnnotation(EventHandler.class);
                } while(eh == null);

                Class checkClass;
                if (method.getParameterTypes().length == 1 && Event.class.isAssignableFrom(checkClass = method.getParameterTypes()[0])) {
                    Class<? extends Event> eventClass = checkClass.asSubclass(Event.class);
                    method.setAccessible(true);
                    Set<RegisteredListener> eventSet = (Set)ret.get(eventClass);
                    if (eventSet == null) {
                        eventSet = new HashSet();
                        ret.put(eventClass, eventSet);
                    }

                    for(Class clazz = eventClass; Event.class.isAssignableFrom(clazz); clazz = clazz.getSuperclass()) {
                        if (clazz.getAnnotation(Deprecated.class) != null) {
                            Warning warning = (Warning)clazz.getAnnotation(Warning.class);
                            WarningState warningState = this.server.getWarningState();
                            if (warningState.printFor(warning)) {
                                plugin.getLogger().log(Level.WARNING, String.format("\"%s\" has registered a listener for %s on method \"%s\", but the event is Deprecated. \"%s\"; please notify the authors %s.", plugin.getDescription().getFullName(), clazz.getName(), method.toGenericString(), warning != null && warning.reason().length() != 0 ? warning.reason() : "Server performance will be affected", Arrays.toString(plugin.getDescription().getAuthors().toArray())), warningState == WarningState.ON ? new AuthorNagException((String)null) : null);
                            }
                            break;
                        }
                    }

                    CustomTimingsHandler timings = new CustomTimingsHandler("Plugin: " + plugin.getDescription().getFullName() + " Event: " + listener.getClass().getName() + "::" + method.getName() + "(" + eventClass.getSimpleName() + ")", pluginParentTimer);
                    EventExecutor executor = new 1(this, eventClass, timings, method);
                    ((Set)eventSet).add(new RegisteredListener(listener, executor, eh.priority(), plugin, eh.ignoreCancelled()));
                } else {
                    plugin.getLogger().severe(plugin.getDescription().getFullName() + " attempted to register an invalid EventHandler method signature \"" + method.toGenericString() + "\" in " + listener.getClass());
                }
            }
        }
    }

    public void enablePlugin(Plugin plugin) {
        Validate.isTrue(plugin instanceof JavaPlugin, "Plugin is not associated with this PluginLoader");
        if (!plugin.isEnabled()) {
            plugin.getLogger().info("Enabling " + plugin.getDescription().getFullName());
            JavaPlugin jPlugin = (JavaPlugin)plugin;
            String pluginName = jPlugin.getDescription().getName();
            if (!this.loaders.containsKey(pluginName)) {
                this.loaders.put(pluginName, (PluginClassLoader)jPlugin.getClassLoader());
            }

            try {
                jPlugin.setEnabled(true);
            } catch (Throwable var5) {
                this.server.getLogger().log(Level.SEVERE, "Error occurred while enabling " + plugin.getDescription().getFullName() + " (Is it up to date?)", var5);
            }

            this.server.getPluginManager().callEvent(new PluginEnableEvent(plugin));
        }

    }

    public void disablePlugin(Plugin plugin) {
        Validate.isTrue(plugin instanceof JavaPlugin, "Plugin is not associated with this PluginLoader");
        if (plugin.isEnabled()) {
            String message = String.format("Disabling %s", plugin.getDescription().getFullName());
            plugin.getLogger().info(message);
            this.server.getPluginManager().callEvent(new PluginDisableEvent(plugin));
            JavaPlugin jPlugin = (JavaPlugin)plugin;
            ClassLoader cloader = jPlugin.getClassLoader();

            try {
                jPlugin.setEnabled(false);
            } catch (Throwable var9) {
                this.server.getLogger().log(Level.SEVERE, "Error occurred while disabling " + plugin.getDescription().getFullName() + " (Is it up to date?)", var9);
            }

            this.loaders.remove(jPlugin.getDescription().getName());
            if (cloader instanceof PluginClassLoader) {
                PluginClassLoader loader = (PluginClassLoader)cloader;
                Set<String> names = loader.getClasses();
                Iterator var7 = names.iterator();

                while(var7.hasNext()) {
                    String name = (String)var7.next();
                    this.removeClass(name);
                }
            }
        }

    }

    public InheritanceMap getGlobalInheritanceMap() {
        if (this.globalInheritanceMap == null) {
            Map<String, String> relocationsCurrent = new HashMap();
            relocationsCurrent.put("net.minecraft.server", "net.minecraft.server." + PluginClassLoader.getNativeVersion());
            JarMapping currentMappings = new JarMapping();

            try {
                currentMappings.loadMappings(new BufferedReader(new InputStreamReader(this.getClass().getClassLoader().getResourceAsStream("mappings/" + PluginClassLoader.getNativeVersion() + "/cb2numpkg.srg"))), new MavenShade(relocationsCurrent), (MappingTransformer)null, false);
            } catch (IOException var7) {
                var7.printStackTrace();
                throw new RuntimeException(var7);
            }

            BiMap<String, String> inverseClassMap = HashBiMap.create(currentMappings.classes).inverse();
            this.globalInheritanceMap = new InheritanceMap();
            BufferedReader reader = new BufferedReader(new InputStreamReader(this.getClass().getClassLoader().getResourceAsStream("mappings/" + PluginClassLoader.getNativeVersion() + "/nms.inheritmap")));

            try {
                this.globalInheritanceMap.load(reader, inverseClassMap);
            } catch (IOException var6) {
                var6.printStackTrace();
                throw new RuntimeException(var6);
            }

            System.out.println("Loaded inheritance map of " + this.globalInheritanceMap.size() + " classes");
        }

        return this.globalInheritanceMap;
    }
}
