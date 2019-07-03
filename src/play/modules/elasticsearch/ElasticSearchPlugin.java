/**
 * Copyright 2011 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 * @author Felipe Oliveira (http://mashup.fm)
 */
package play.modules.elasticsearch;

import static java.util.concurrent.TimeUnit.MINUTES;
import static pl.allegro.tech.embeddedelasticsearch.PopularProperties.CLUSTER_NAME;
import static pl.allegro.tech.embeddedelasticsearch.PopularProperties.HTTP_PORT;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang.Validate;
import org.apache.http.HttpHost;
import org.elasticsearch.client.Node;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.Settings.Builder;
import pl.allegro.tech.embeddedelasticsearch.EmbeddedElastic;
import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.db.Model;
import play.modules.elasticsearch.ElasticSearchIndexEvent.Type;
import play.modules.elasticsearch.adapter.ElasticSearchAdapter;
import play.modules.elasticsearch.annotations.ElasticSearchable;
import play.modules.elasticsearch.mapping.MapperFactory;
import play.modules.elasticsearch.mapping.MappingException;
import play.modules.elasticsearch.mapping.MappingUtil;
import play.modules.elasticsearch.mapping.ModelMapper;
import play.modules.elasticsearch.mapping.impl.DefaultMapperFactory;
import play.modules.elasticsearch.util.ExceptionUtil;
import play.modules.elasticsearch.util.ReflectionUtil;
import play.mvc.Router;

// TODO: Auto-generated Javadoc

/**
 * The Class ElasticSearchPlugin.
 */
public class ElasticSearchPlugin extends PlayPlugin {

  private static final String EMBEDDED_ELASTIC_VERSION = "6.5.0";
  private static final String EMBEDDED_CLUSTER_NAME = "Embedded-Elastic";
  private static final int EMBEDDED_HTTP_PORT_VALUE = 9999;

  private static EmbeddedElastic embeddedElastic = null;

  /**
   * The mapper factory
   */
  private static MapperFactory mapperFactory = new DefaultMapperFactory();

  private static volatile ElasticSearchDeliveryMode currentDeliveryMode;

  /**
   * The mappers index.
   */
  private static Map<Class<?>, ModelMapper<?>> mappers = null;

  /**
   * The started indices.
   */
  private static Set<Class<?>> indicesStarted = null;

  /**
   * Index type -> Class lookup
   */
  private static Map<String, Class<?>> modelLookup = null;

  /**
   * The client.
   */
  private static RestHighLevelClient client = null;

  /**
   * Client.
   *
   * @return the client
   */
  public static RestHighLevelClient client() {
    return client;
  }

  public static boolean isEnabled() {
    return client != null;
  }

  public static void setMapperFactory(final MapperFactory factory) {
    mapperFactory = factory;
    mappers.clear();
  }

  /**
   * Checks if is local mode.
   *
   * @return true, if is local mode
   */
  private boolean isLocalMode() {
    try {
      final String client = Play.configuration.getProperty("elasticsearch.client");
      final Boolean local = Boolean
          .getBoolean(Play.configuration.getProperty("elasticsearch.local", "true"));

      if (client == null) {
        return true;
      }
      if (client.equalsIgnoreCase("false") || client.equalsIgnoreCase("true")) {
        return true;
      }

      return local;
    } catch (final Exception e) {
      Logger.error("Error! Starting in Local Model: %s", ExceptionUtil.getStackTrace(e));
      return true;
    }
  }

  private boolean isRestMode() {
    return Boolean
        .getBoolean(
            Play.configuration.getProperty("elasticsearch.rest", "false")
        );
  }

  /**
   * Gets the hosts.
   *
   * @return the hosts
   */
  public static String getHosts() {
    final String s = Play.configuration.getProperty("elasticsearch.client");
    if (s == null) {
      return "";
    }
    return s;
  }

  private static List<HttpHost> getHttpHosts() {
    final List<HttpHost> httpHosts = new ArrayList<>();
    final String[] hosts = getHosts().trim().split(",");
    for (final String host : hosts) {
      final String[] parts = host.split(":");
      if (parts.length != 2) {
        throw new RuntimeException("Invalid Host: " + host);
      }

      String hostName = parts[0];
      Integer port = Integer.valueOf(parts[1]);
      String protocol = port == 443 ? "https" : "http";
      httpHosts.add(new HttpHost(hostName, port, protocol));
    }

    return httpHosts;
  }

  public static String getClusterName() {
    final String s = Play.configuration.getProperty("elasticsearch.cluster.name");
    if (s == null) {
      return "";
    }
    return s;
  }

  public static ElasticSearchDeliveryMode getDeliveryMode() {
    return currentDeliveryMode;
  }

  public static void setDeliveryMode(final ElasticSearchDeliveryMode deliveryMode) {
    currentDeliveryMode = deliveryMode;
  }

  /**
   * Gets the delivery mode from the configuration.
   *
   * @return the delivery mode
   */
  public static ElasticSearchDeliveryMode getDeliveryModeFromConfiguration() {
    final String s = Play.configuration.getProperty("elasticsearch.delivery");
    if (s == null) {
      return ElasticSearchDeliveryMode.LOCAL;
    }
    if ("CUSTOM".equals(s)) {
      return ElasticSearchDeliveryMode.createCustomIndexEventHandler(Play.configuration
          .getProperty("elasticsearch.customIndexEventHandler",
              "play.modules.elasticsearch.LocalIndexEventHandler"));
    }
    return ElasticSearchDeliveryMode.valueOf(s.toUpperCase());
  }

  /**
   * This method is called when the application starts - It will start ES instance
   *
   * @see play.PlayPlugin#onApplicationStart()
   */
  @Override
  public void onApplicationStart() {
    // (re-)set caches
    mappers = new ConcurrentHashMap<>();
    modelLookup = new ConcurrentHashMap<>();
    indicesStarted = Collections.newSetFromMap(new HashMap<Class<?>, Boolean>());
    ReflectionUtil.clearCache();

    String enabled = Play.configuration.getProperty("elasticsearch.enabled", "true");
    if (!"true".equalsIgnoreCase(enabled)) {
      Logger.info("Elasticsearch module disabled");
      return;
    }

    // Make sure it doesn't get started more than once
    if ((client != null)) {
      Logger.debug("Elastic Search Started Already!");
      return;
    }

    // Start Node Builder
    final Builder settingsBuilder = Settings.builder();
    settingsBuilder.put("cluster.name", getClusterName());

    // Import anything from play configuration that starts with elasticsearch.native.
    final Enumeration<Object> keys = Play.configuration.keys();
    while (keys.hasMoreElements()) {
      final String key = (String) keys.nextElement();
      if (key.startsWith("elasticsearch.native.")) {
        final String nativeKey = key.replaceFirst("elasticsearch.native.", "");
        Logger
            .error(
                "Adding native [" + nativeKey + "," + Play.configuration.getProperty(key) + "]");
        settingsBuilder.put(nativeKey, Play.configuration.getProperty(key));
      }
    }

    // Check Model
    if (this.isLocalMode()) {
      Logger.info("Connecting Play! to Elastic Search in embedded mode");
      try {
        if (embeddedElastic != null) {
          embeddedElastic.stop();
        }
        embeddedElastic = EmbeddedElastic.builder()
            .withElasticVersion(EMBEDDED_ELASTIC_VERSION)
            .withSetting(HTTP_PORT, EMBEDDED_HTTP_PORT_VALUE)
            .withSetting(CLUSTER_NAME, EMBEDDED_CLUSTER_NAME)
            .withEsJavaOpts("-Xms128m -Xmx512m")
            .withStartTimeout(2, MINUTES)
            .withCleanInstallationDirectoryOnStop(false)
            .build()
            .start();
      } catch (IOException | InterruptedException e) {
        throw new RuntimeException("Unable to start Elastic Search in Embedded mode");
      }
      client = new RestHighLevelClient(
          RestClient.builder(
              new HttpHost("localhost", EMBEDDED_HTTP_PORT_VALUE, "http")
          ));
    } else {
      Logger.info("Connecting Play! to Elastic Search in rest client mode");
      if (Play.configuration.getProperty("elasticsearch.client") == null) {
        throw new RuntimeException(
            "Configuration required - elasticsearch.client when local model is disabled!");
      }

      List<HttpHost> hosts = getHttpHosts();
      List<Node> nodes = new ArrayList(hosts.size());
      for (HttpHost host : hosts) {
        nodes.add(new Node(host));
      }
      client = new RestHighLevelClient(
          RestClient.builder(nodes.toArray(new Node[0]))
      );
    }

    // Configure current delivery mode
    setDeliveryMode(getDeliveryModeFromConfiguration());

    // Bind Admin
    Router.addRoute("GET", "/es-admin", "elasticsearch.ElasticSearchAdmin.index");

    // Check Client
    if (client == null) {
      throw new RuntimeException(
          "Elastic Search Client cannot be null - please check the configuration provided and the health of your Elastic Search instances.");
    }
  }

  @Override
  public void onApplicationStop() {
    if (embeddedElastic != null) {
      embeddedElastic.stop();
    }

    if (client != null) {
      try {
        client.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
      client = null;
    }
  }

  @SuppressWarnings("unchecked")
  public static <M> ModelMapper<M> getMapper(final Class<M> clazz) {
    if (mappers.containsKey(clazz)) {
      return (ModelMapper<M>) mappers.get(clazz);
    }

    final ModelMapper<M> mapper = mapperFactory.getMapper(clazz);
    mappers.put(clazz, mapper);
    modelLookup.put(mapper.getTypeName(), clazz);

    return mapper;
  }

  private static void startIndexIfNeeded(final Class<Model> clazz) {
    if (!indicesStarted.contains(clazz)) {
      final ModelMapper<Model> mapper = getMapper(clazz);
      Logger.info("Start Index for Class: %s", clazz);
      ElasticSearchAdapter.startIndex(client(), mapper);
      indicesStarted.add(clazz);
    }
  }

  private static boolean isInterestingEvent(final String event) {
    return event.endsWith(".objectPersisted") || event.endsWith(".objectUpdated") || event
        .endsWith(".objectDeleted");
  }

  /**
   * This is the method that will be sending data to ES instance
   *
   * @see play.PlayPlugin#onEvent(java.lang.String, java.lang.Object)
   */
  @Override
  public void onEvent(final String message, final Object context) {
    if (!isEnabled() || isInterestingEvent(message) == false) {
      return;
    }

    // Check if object is searchable
    if (MappingUtil.isSearchable(context.getClass()) == false) {
      return;
    }

    // Sanity check, we only index models
    Validate.isTrue(context instanceof Model, "Only play.db.Model subclasses can be indexed");

    // Start index if needed
    @SuppressWarnings("unchecked") final Class<Model> clazz = (Class<Model>) context.getClass();
    startIndexIfNeeded(clazz);

    // Define Event
    ElasticSearchIndexEvent event = null;
    if (message.endsWith(".objectPersisted") || message.endsWith(".objectUpdated")) {
      // Index Model
      event = new ElasticSearchIndexEvent((Model) context, ElasticSearchIndexEvent.Type.INDEX);

    } else if (message.endsWith(".objectDeleted")) {
      // Delete Model from Index
      event = new ElasticSearchIndexEvent((Model) context, ElasticSearchIndexEvent.Type.DELETE);
    }

    // Sync with Elastic Search
    Logger.info("Elastic Search Index Event: %s", event);
    if (event != null) {
      final ElasticSearchDeliveryMode deliveryMode = getDeliveryMode();
      final IndexEventHandler handler = deliveryMode.getHandler();
      handler.handle(event);
    }
  }

  <M extends Model> void index(final M model) {
    final ElasticSearchDeliveryMode deliveryMode = getDeliveryMode();
    index(model, deliveryMode);
  }

  public <M extends Model> void index(final M model,
      final ElasticSearchDeliveryMode deliveryMode) {
    @SuppressWarnings("unchecked") final Class<Model> clazz = (Class<Model>) model.getClass();

    if (!ElasticSearchPlugin.isEnabled()) {
      return;
    }

    // Check if object is searchable
    if (MappingUtil.isSearchable(clazz) == false) {
      throw new IllegalArgumentException("model is not searchable");
    }

    startIndexIfNeeded(clazz);

    final ElasticSearchIndexEvent event = new ElasticSearchIndexEvent(model, Type.INDEX);
    final IndexEventHandler handler = deliveryMode.getHandler();
    handler.handle(event);
  }

  /**
   * Looks up the model class based on the index type name
   *
   * @return Class of the Model
   */
  public static Class<?> lookupModel(final String indexType) {
    final Class<?> clazz = modelLookup.get(indexType);
    if (clazz != null) { // we have not cached this model yet
      return clazz;
    }
    final List<Class> searchableModels = Play.classloader
        .getAnnotatedClasses(ElasticSearchable.class);
    for (final Class searchableModel : searchableModels) {
      try {
        if (indexType.equals(getMapper(searchableModel).getTypeName())) {
          return searchableModel;
        }
      } catch (final MappingException ex) {
        // mapper can not be retrieved
      }
    }
    throw new IllegalArgumentException("Type name '" + indexType + "' is not searchable!");
  }

}
