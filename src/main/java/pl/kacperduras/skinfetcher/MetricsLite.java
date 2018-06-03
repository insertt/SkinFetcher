package pl.kacperduras.skinfetcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.zip.GZIPOutputStream;

/**
 * bStats collects some data for plugin authors.
 *
 * Check out https://bStats.org/ to learn more about bStats!
 */
public class MetricsLite {

  // The version of this bStats class
  public static final int B_STATS_VERSION = 1;

  // The url to which the data is sent
  private static final String URL = "https://bStats.org/submitData/bungeecord";

  // The plugin
  private final Plugin plugin;

  // Is bStats enabled on this server?
  private boolean enabled;

  // The uuid of the server
  private String serverUUID;

  // Should failed requests be logged?
  private boolean logFailedRequests = false;

  // A list with all known metrics class objects including this one
  private static final List<Object> knownMetricsInstances = new ArrayList<>();

  public MetricsLite(Plugin plugin) {
    this.plugin = plugin;

    try {
      loadConfig();
    } catch (IOException e) {
      // Failed to load configuration
      plugin.getLogger().log(Level.WARNING, "Failed to load bStats config!", e);
      return;
    }

    // We are not allowed to send data about this server :(
    if (!enabled) {
      return;
    }

    Class<?> usedMetricsClass = getFirstBStatsClass();
    if (usedMetricsClass == null) {
      // Failed to get first metrics class
      return;
    }
    if (usedMetricsClass == getClass()) {
      // We are the first! :)
      linkMetrics(this);
      startSubmitting();
    } else {
      // We aren't the first so we link to the first metrics class
      try {
        usedMetricsClass.getMethod("linkMetrics", Object.class).invoke(null,this);
      } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
        if (logFailedRequests) {
          plugin.getLogger().log(Level.WARNING, "Failed to link to first metrics class " + usedMetricsClass.getName() + "!", e);
        }
      }
    }
  }

  /**
   * Links an other metrics class with this class.
   * This method is called using Reflection.
   *
   * @param metrics An object of the metrics class to link.
   */
  public static void linkMetrics(Object metrics) {
    knownMetricsInstances.add(metrics);
  }

  /**
   * Gets the plugin specific data.
   * This method is called using Reflection.
   *
   * @return The plugin specific data.
   */
  public JsonObject getPluginData() {
    JsonObject data = new JsonObject();

    String pluginName = plugin.getDescription().getName();
    String pluginVersion = plugin.getDescription().getVersion();

    data.addProperty("pluginName", pluginName);
    data.addProperty("pluginVersion", pluginVersion);

    JsonArray customCharts = new JsonArray();
    data.add("customCharts", customCharts);

    return data;
  }

  private void startSubmitting() {
    plugin.getProxy().getScheduler().schedule(plugin, new Runnable() {
      @Override
      public void run() {
        // The data collection is async, as well as sending the data
        // Bungeecord does not have a main thread, everything is async
        submitData();
      }
    }, 2, 30, TimeUnit.MINUTES);
    // Submit the data every 30 minutes, first time after 2 minutes to give other plugins enough time to start
    // WARNING: Changing the frequency has no effect but your plugin WILL be blocked/deleted!
    // WARNING: Just don't do it!
  }

  /**
   * Gets the server specific data.
   *
   * @return The server specific data.
   */
  private JsonObject getServerData() {
    // Minecraft specific data
    int playerAmount = plugin.getProxy().getOnlineCount();
    playerAmount = playerAmount > 500 ? 500 : playerAmount;
    int onlineMode = plugin.getProxy().getConfig().isOnlineMode() ? 1 : 0;
    String bungeecordVersion = plugin.getProxy().getVersion();
    int managedServers = plugin.getProxy().getServers().size();

    // OS/Java specific data
    String javaVersion = System.getProperty("java.version");
    String osName = System.getProperty("os.name");
    String osArch = System.getProperty("os.arch");
    String osVersion = System.getProperty("os.version");
    int coreCount = Runtime.getRuntime().availableProcessors();

    JsonObject data = new JsonObject();

    data.addProperty("serverUUID", serverUUID);

    data.addProperty("playerAmount", playerAmount);
    data.addProperty("managedServers", managedServers);
    data.addProperty("onlineMode", onlineMode);
    data.addProperty("bungeecordVersion", bungeecordVersion);

    data.addProperty("javaVersion", javaVersion);
    data.addProperty("osName", osName);
    data.addProperty("osArch", osArch);
    data.addProperty("osVersion", osVersion);
    data.addProperty("coreCount", coreCount);

    return data;
  }

  /**
   * Collects the data and sends it afterwards.
   */
  private void submitData() {
    final JsonObject data = getServerData();

    final JsonArray pluginData = new JsonArray();
    // Search for all other bStats Metrics classes to get their plugin data
    for (Object metrics : knownMetricsInstances) {
      try {
        Object plugin = metrics.getClass().getMethod("getPluginData").invoke(metrics);
        if (plugin instanceof JsonObject) {
          pluginData.add((JsonObject) plugin);
        }
      } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) { }
    }

    data.add("plugins", pluginData);

    try {
      // Send the data
      sendData(data);
    } catch (Exception e) {
      // Something went wrong! :(
      if (logFailedRequests) {
        plugin.getLogger().log(Level.WARNING, "Could not submit plugin stats!", e);
      }
    }
  }

  /**
   * Loads the bStats configuration.
   *
   * @throws IOException If something did not work :(
   */
  private void loadConfig() throws IOException {
    Path configPath = plugin.getDataFolder().toPath().getParent().resolve("bStats");
    configPath.toFile().mkdirs();
    File configFile = new File(configPath.toFile(), "config.yml");
    if (!configFile.exists()) {
      writeFile(configFile,
          "#bStats collects some data for plugin authors like how many servers are using their plugins.",
          "#To honor their work, you should not disable it.",
          "#This has nearly no effect on the server performance!",
          "#Check out https://bStats.org/ to learn more :)",
          "enabled: true",
          "serverUuid: \"" + UUID.randomUUID().toString() + "\"",
          "logFailedRequests: false");
    }

    Configuration configuration = ConfigurationProvider.getProvider(YamlConfiguration.class).load(configFile);

    // Load configuration
    enabled = configuration.getBoolean("enabled", true);
    serverUUID = configuration.getString("serverUuid");
    logFailedRequests = configuration.getBoolean("logFailedRequests", false);
  }

  /**
   * Gets the first bStat Metrics class.
   *
   * @return The first bStats metrics class.
   */
  private Class<?> getFirstBStatsClass() {
    Path configPath = plugin.getDataFolder().toPath().getParent().resolve("bStats");
    configPath.toFile().mkdirs();
    File tempFile = new File(configPath.toFile(), "temp.txt");

    try {
      String className = readFile(tempFile);
      if (className != null) {
        try {
          // Let's check if a class with the given name exists.
          return Class.forName(className);
        } catch (ClassNotFoundException ignored) { }
      }
      writeFile(tempFile, getClass().getName());
      return getClass();
    } catch (IOException e) {
      if (logFailedRequests) {
        plugin.getLogger().log(Level.WARNING, "Failed to get first bStats class!", e);
      }
      return null;
    }
  }

  /**
   * Reads the first line of the file.
   *
   * @param file The file to read. Cannot be null.
   * @return The first line of the file or <code>null</code> if the file does not exist or is empty.
   * @throws IOException If something did not work :(
   */
  private String readFile(File file) throws IOException {
    if (!file.exists()) {
      return null;
    }
    try (
        FileReader fileReader = new FileReader(file);
        BufferedReader bufferedReader =  new BufferedReader(fileReader);
    ) {
      return bufferedReader.readLine();
    }
  }

  /**
   * Writes a String to a file. It also adds a note for the user,
   *
   * @param file The file to write to. Cannot be null.
   * @param lines The lines to write.
   * @throws IOException If something did not work :(
   */
  private void writeFile(File file, String... lines) throws IOException {
    if (!file.exists()) {
      file.createNewFile();
    }
    try (
        FileWriter fileWriter = new FileWriter(file);
        BufferedWriter bufferedWriter = new BufferedWriter(fileWriter)
    ) {
      for (String line : lines) {
        bufferedWriter.write(line);
        bufferedWriter.newLine();
      }
    }
  }

  /**
   * Sends the data to the bStats server.
   *
   * @param data The data to send.
   * @throws Exception If the request failed.
   */
  private static void sendData(JsonObject data) throws Exception {
    if (data == null) {
      throw new IllegalArgumentException("Data cannot be null");
    }

    HttpsURLConnection connection = (HttpsURLConnection) new URL(URL).openConnection();

    // Compress the data to save bandwidth
    byte[] compressedData = compress(data.toString());

    // Add headers
    connection.setRequestMethod("POST");
    connection.addRequestProperty("Accept", "application/json");
    connection.addRequestProperty("Connection", "close");
    connection.addRequestProperty("Content-Encoding", "gzip"); // We gzip our request
    connection.addRequestProperty("Content-Length", String.valueOf(compressedData.length));
    connection.setRequestProperty("Content-Type", "application/json"); // We send our data in JSON format
    connection.setRequestProperty("User-Agent", "MC-Server/" + B_STATS_VERSION);

    // Send data
    connection.setDoOutput(true);
    DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream());
    outputStream.write(compressedData);
    outputStream.flush();
    outputStream.close();

    connection.getInputStream().close(); // We don't care about the response - Just send our data :)
  }

  /**
   * Gzips the given String.
   *
   * @param str The string to gzip.
   * @return The gzipped String.
   * @throws IOException If the compression failed.
   */
  private static byte[] compress(final String str) throws IOException {
    if (str == null) {
      return null;
    }
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    GZIPOutputStream gzip = new GZIPOutputStream(outputStream);
    gzip.write(str.getBytes("UTF-8"));
    gzip.close();
    return outputStream.toByteArray();
  }

}
