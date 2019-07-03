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

import org.elasticsearch.client.RestHighLevelClient;
import play.Play;
import play.db.Model;
import play.libs.F.Promise;

/**
 * The Class ElasticSearch.
 */
public abstract class ElasticSearch {

  /**
   * Client.
   *
   * @return the client
   */
  public static RestHighLevelClient client() {
    return ElasticSearchPlugin.client();
  }

  /**
   * Indexes the given model
   *
   * @param <T> the model type
   * @param model the model
   */
  public static <T extends Model> void index(final T model) {
    final ElasticSearchPlugin plugin = Play.plugin(ElasticSearchPlugin.class);
    plugin.index(model);
  }

  /**
   * Indexes the given model using delivery mode
   *
   * @param <T> the model type
   * @param model the model
   */
  public static <T extends Model> void index(final T model,
      final ElasticSearchDeliveryMode deliveryMode) {
    final ElasticSearchPlugin plugin = Play.plugin(ElasticSearchPlugin.class);
    plugin.index(model, deliveryMode);
  }

  /**
   * Reindexes the given model using provided delivery mode
   *
   * @param deliveryMode Delivery mode to use for reindexing tasks. Set null to use the default,
   * synchronous mode.
   * @param model the model
   */
  public static Promise<Void> reindex(final ElasticSearchDeliveryMode deliveryMode) {
    return new ReindexDatabaseJob(deliveryMode).now();
  }

}
