package com.slack.kaldb.elasticsearchApi;

import brave.ScopedSpan;
import brave.Tracing;
import brave.propagation.TraceContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.protobuf.ByteString;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.Post;
import com.slack.kaldb.elasticsearchApi.searchResponse.EsSearchResponse;
import com.slack.kaldb.elasticsearchApi.searchResponse.HitsMetadata;
import com.slack.kaldb.elasticsearchApi.searchResponse.SearchResponseHit;
import com.slack.kaldb.elasticsearchApi.searchResponse.SearchResponseMetadata;
import com.slack.kaldb.logstore.LogMessage;
import com.slack.kaldb.logstore.opensearch.OpenSearchInternalAggregation;
import com.slack.kaldb.logstore.search.SearchResultUtils;
import com.slack.kaldb.metadata.schema.FieldType;
import com.slack.kaldb.proto.service.KaldbSearch;
import com.slack.kaldb.server.KaldbQueryServiceBase;
import com.slack.kaldb.util.JsonUtil;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.opensearch.search.aggregations.InternalAggregation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Elasticsearch compatible API service, for use in Grafana
 *
 * @see <a
 *     href="https://github.com/grafana/grafana/blob/main/public/app/plugins/datasource/elasticsearch/datasource.ts">Grafana
 *     ES API</a>
 */
@SuppressWarnings(
    "OptionalUsedAsFieldOrParameterType") // Per https://armeria.dev/docs/server-annotated-service/
public class ElasticsearchApiService {
  private static final Logger LOG = LoggerFactory.getLogger(ElasticsearchApiService.class);
  private final KaldbQueryServiceBase searcher;

  // This uses a separate cached threadpool for multisearch queries so that we can run these in
  // parallel. A cached threadpool was chosen over something like forkjoin, as it's easier to
  // propagate the trace instrumentation, and has better visibility using a custom threadfactory.
  private final ExecutorService multisearchExecutor =
      Executors.newCachedThreadPool(
          new ThreadFactoryBuilder()
              .setUncaughtExceptionHandler(
                  (t, e) -> LOG.error("Exception on thread {}: {}", t.getName(), e))
              .setNameFormat("elasticsearch-multisearch-api-%d")
              .build());

  private final OpenSearchRequest openSearchRequest = new OpenSearchRequest();
  private final ObjectMapper objectMapper = new ObjectMapper();

  public ElasticsearchApiService(KaldbQueryServiceBase searcher) {
    this.searcher = searcher;
  }

  /**
   * Multisearch API
   *
   * @see <a
   *     href="https://www.elastic.co/guide/en/elasticsearch/reference/current/search-multi-search.html">API
   *     doc</a>
   */
  @Post
  @Blocking
  @Path("/_msearch")
  public HttpResponse multiSearch(String postBody) throws Exception {
    LOG.debug("Search request: {}", postBody);

    List<KaldbSearch.SearchRequest> requests = openSearchRequest.parseHttpPostBody(postBody);
    List<ListenableFuture<EsSearchResponse>> responseFutures =
        requests.stream()
            .map(
                (request) ->
                    Futures.submit(
                        () -> this.doSearch(request),
                        Tracing.current().currentTraceContext().executor(multisearchExecutor)))
            .collect(Collectors.toList());

    SearchResponseMetadata responseMetadata =
        new SearchResponseMetadata(
            0, Futures.allAsList(responseFutures).get(), Map.of("traceId", getTraceId()));
    return HttpResponse.of(
        HttpStatus.OK, MediaType.JSON_UTF_8, JsonUtil.writeAsString(responseMetadata));
  }

  private EsSearchResponse doSearch(KaldbSearch.SearchRequest searchRequest) {
    ScopedSpan span = Tracing.currentTracer().startScopedSpan("ElasticsearchApiService.doSearch");
    KaldbSearch.SearchResult searchResult = searcher.doSearch(searchRequest);

    span.tag("requestDataset", searchRequest.getDataset());
    span.tag("requestQueryString", searchRequest.getQueryString());
    span.tag("requestQueryStartTimeEpochMs", String.valueOf(searchRequest.getStartTimeEpochMs()));
    span.tag("requestQueryEndTimeEpochMs", String.valueOf(searchRequest.getEndTimeEpochMs()));
    span.tag("requestHowMany", String.valueOf(searchRequest.getHowMany()));
    span.tag("resultHitsCount", String.valueOf(searchResult.getHitsCount()));
    span.tag("resultTookMicros", String.valueOf(searchResult.getTookMicros()));
    span.tag("resultFailedNodes", String.valueOf(searchResult.getFailedNodes()));
    span.tag("resultTotalNodes", String.valueOf(searchResult.getTotalNodes()));
    span.tag("resultTotalSnapshots", String.valueOf(searchResult.getTotalNodes()));
    span.tag(
        "resultSnapshotsWithReplicas", String.valueOf(searchResult.getSnapshotsWithReplicas()));

    try {
      HitsMetadata hits = getHits(searchResult);
      return new EsSearchResponse.Builder()
          .hits(hits)
          .aggregations(parseAggregations(searchResult.getInternalAggregations()))
          .took(Duration.of(searchResult.getTookMicros(), ChronoUnit.MICROS).toMillis())
          .shardsMetadata(searchResult.getTotalNodes(), searchResult.getFailedNodes())
          .debugMetadata(Map.of())
          .status(200)
          .build();
    } catch (Exception e) {
      LOG.error("Error fulfilling request for multisearch query", e);
      span.error(e);
      return new EsSearchResponse.Builder()
          .took(Duration.of(searchResult.getTookMicros(), ChronoUnit.MICROS).toMillis())
          .shardsMetadata(searchResult.getTotalNodes(), searchResult.getFailedNodes())
          .status(500)
          .build();
    } finally {
      span.finish();
    }
  }

  private JsonNode parseAggregations(ByteString byteInput) throws IOException {
    InternalAggregation internalAggregations =
        OpenSearchInternalAggregation.fromByteArray(byteInput.toByteArray());
    if (internalAggregations != null) {
      return objectMapper.readTree(internalAggregations.toString());
    }
    return null;
  }

  private String getTraceId() {
    TraceContext traceContext = Tracing.current().currentTraceContext().get();
    if (traceContext != null) {
      return traceContext.traceIdString();
    }
    return "";
  }

  private HitsMetadata getHits(KaldbSearch.SearchResult searchResult) throws IOException {
    List<ByteString> hitsByteList = searchResult.getHitsList().asByteStringList();
    List<SearchResponseHit> responseHits = new ArrayList<>(hitsByteList.size());
    for (ByteString bytes : hitsByteList) {
      responseHits.add(SearchResponseHit.fromByteString(bytes));
    }

    return new HitsMetadata.Builder()
        .hitsTotal(ImmutableMap.of("value", responseHits.size(), "relation", "eq"))
        .hits(responseHits)
        .build();
  }

  /**
   * Mapping API
   *
   * @see <a
   *     href="https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-get-mapping.html">API
   *     doc</a>
   */
  @Get
  @Path("/:indexName/_mapping")
  public HttpResponse mapping(
      @Param("indexName") Optional<String> indexName,
      @Param("startTimeEpochMs") Optional<Long> startTimeEpochMs,
      @Param("endTimeEpochMs") Optional<Long> endTimeEpochMs)
      throws IOException {
    // Use a tree map so the results are naturally sorted
    Map<String, Map<String, String>> propertiesMap = new TreeMap<>();

    // we default the schema search to the last hour if params are not provided
    KaldbSearch.SchemaResult schemaResult =
        searcher.getSchema(
            KaldbSearch.SchemaRequest.newBuilder()
                .setDataset(indexName.orElse("*"))
                .setStartTimeEpochMs(
                    startTimeEpochMs.orElse(
                        Instant.now().minus(1, ChronoUnit.HOURS).toEpochMilli()))
                .setEndTimeEpochMs(endTimeEpochMs.orElse(Instant.now().toEpochMilli()))
                .build());

    Map<String, FieldType> schema = SearchResultUtils.fromSchemaResultProto(schemaResult);
    schema.forEach((key, value) -> propertiesMap.put(key, Map.of("type", value.getName())));

    // todo - remove this after we add support for a "date" type
    // override the timestamp as a date field for proper autocomplete
    propertiesMap.put(LogMessage.SystemField.TIME_SINCE_EPOCH.fieldName, Map.of("type", "date"));

    return HttpResponse.of(
        HttpStatus.OK,
        MediaType.JSON,
        JsonUtil.writeAsString(
            ImmutableMap.of(
                indexName.orElseThrow(),
                ImmutableMap.of("mappings", ImmutableMap.of("properties", propertiesMap)))));
  }
}
