package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import java.io.Reader;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.synonym.SynonymFilter;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.assistedinject.Assisted;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.analysis.AbstractTokenFilterFactory;
import org.elasticsearch.index.analysis.AnalysisSettingsRequired;
import org.elasticsearch.index.analysis.TokenizerFactory;
import org.elasticsearch.index.analysis.TokenizerFactoryFactory;
import org.elasticsearch.index.settings.IndexSettings;
import org.elasticsearch.indices.IndicesLifecycle;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.analysis.IndicesAnalysisService;

import com.bellszhu.elasticsearch.cfg.Configuration;

@AnalysisSettingsRequired
public class DynamicSynonymTokenFilterFactory extends
		AbstractTokenFilterFactory {

	public static ESLogger logger = Loggers.getLogger("dynamic-synonym");

	private String location;

	private final String indexName;
	private static ScheduledExecutorService pool = Executors
			.newScheduledThreadPool(1);
	private volatile ScheduledFuture scheduledFuture;
	

	private SynonymMap synonymMap;
	private final boolean ignoreCase;
	private final boolean expand;
	private final String format;
	private final int interval;
	private final Analyzer analyzer;

	private Configuration configuration;

	@Inject
	public DynamicSynonymTokenFilterFactory(Index index,
			@IndexSettings Settings indexSettings, Environment env,
			IndicesAnalysisService indicesAnalysisService,
			Map<String, TokenizerFactoryFactory> tokenizerFactories,
			@Assisted String name, @Assisted Settings settings,
			IndicesService indicesService) {
		super(index, indexSettings, name, settings);
		
		this.indexName = index.getName();

		configuration = new Configuration(env);

		this.location = configuration.getSynonymsPath();
		this.interval = configuration.getInterval();
		this.ignoreCase = configuration.getIgnorecase();
		this.expand = configuration.getExpand();
		this.format = configuration.getFormat();
		
		logger.info("*****location: " + location);
		
		String tokenizerName = settings.get("tokenizer", "whitespace");

		TokenizerFactoryFactory tokenizerFactoryFactory = tokenizerFactories
				.get(tokenizerName);
		if (tokenizerFactoryFactory == null) {
			tokenizerFactoryFactory = indicesAnalysisService
					.tokenizerFactoryFactory(tokenizerName);
		}
		if (tokenizerFactoryFactory == null) {
			throw new ElasticsearchIllegalArgumentException(
					"failed to find tokenizer [" + tokenizerName
							+ "] for synonym token filter");
		}

		final TokenizerFactory tokenizerFactory = tokenizerFactoryFactory
				.create(tokenizerName,
						ImmutableSettings.builder().put(indexSettings)
								.put(settings).build());

		this.analyzer = new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName,
					Reader reader) {
				Tokenizer tokenizer = tokenizerFactory == null ? new WhitespaceTokenizer(reader) : tokenizerFactory
						.create(reader);
				TokenStream stream = ignoreCase ? new LowerCaseFilter(tokenizer) : tokenizer;
				return new TokenStreamComponents(tokenizer, stream);
			}
		};
		
		SynonymFile synonymFile = new RemoteSynonymFile();
		synonymMap = synonymFile.createSynonymMap();

		scheduledFuture = pool.scheduleAtFixedRate(new Monitor(
				synonymFile), interval, interval, TimeUnit.SECONDS);
		indicesService.indicesLifecycle().addListener(
				new IndicesLifecycle.Listener() {
					@Override
					public void beforeIndexClosed(IndexService indexService) {
						if (indexService.index().getName().equals(indexName)) {
							scheduledFuture.cancel(false);
						}
					}
				});
	}

	@Override
	public TokenStream create(TokenStream tokenStream) {
		// fst is null means no synonyms
		return synonymMap.fst == null ? tokenStream : new SynonymFilter(
				tokenStream, synonymMap, ignoreCase);
	}
	
	public class Monitor implements Runnable {
		
		private SynonymFile synonymFile;

		public Monitor(SynonymFile synonymFile) {
			this.synonymFile = synonymFile;
		}

		@Override
		public void run() {
			synonymMap = synonymFile.createSynonymMap();
		}
		
	}

}