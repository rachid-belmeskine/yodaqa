package cz.brmlab.yodaqa.pipeline.solrfull;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.*;

import com.google.gson.GsonBuilder;
import cz.brmlab.yodaqa.provider.sqlite.BingResultsCache;
import org.apache.uima.UimaContext;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.cas.AbstractCas;
import org.apache.uima.cas.CAS;
import org.apache.uima.fit.component.JCasMultiplier_ImplBase;
import org.apache.uima.fit.descriptor.ConfigurationParameter;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.util.CasCopier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cz.brmlab.yodaqa.analysis.ansscore.AnswerFV;
import cz.brmlab.yodaqa.flow.asb.MultiThreadASB;
import cz.brmlab.yodaqa.flow.dashboard.QuestionDashboard;
import cz.brmlab.yodaqa.model.CandidateAnswer.AF_ResultRR;
import cz.brmlab.yodaqa.model.Question.Clue;
import cz.brmlab.yodaqa.model.Question.ClueConcept;
import cz.brmlab.yodaqa.model.SearchResult.ResultInfo;
import cz.brmlab.yodaqa.provider.solr.Solr;
import cz.brmlab.yodaqa.provider.solr.SolrQuerySettings;
import org.apache.commons.codec.binary.Base64;


/**
 * Take a question CAS and search for keywords (or already resolved pageIDs)
 * in the Solr data source.  Each search results gets a new CAS.
 *
 * We just feed most of the clues to a Solr search. */

public class BingFullPrimarySearch extends JCasMultiplier_ImplBase {
	final Logger logger = LoggerFactory.getLogger(BingFullPrimarySearch.class);

	/** Number of results to grab and analyze. */
	public static final String PARAM_HITLIST_SIZE = "hitlist-size";
	@ConfigurationParameter(name = PARAM_HITLIST_SIZE, mandatory = false, defaultValue = "6")
	protected int hitListSize;

	/** Number and baseline distance of gradually desensitivized
	 * proximity searches. Total of proximity-num optional search
	 * terms are included, covering proximity-base-dist * #of terms
	 * neighborhood. For each proximity term, the coverage is
	 * successively multiplied by proximity-base-factor; initial weight
	 * is sum of individual weights and is successively halved. */
	public static final String PARAM_PROXIMITY_NUM = "proximity-num";
	@ConfigurationParameter(name = PARAM_PROXIMITY_NUM, mandatory = false, defaultValue = "3")
	protected int proximityNum;
	public static final String PARAM_PROXIMITY_BASE_DIST = "proximity-base-dist";
	@ConfigurationParameter(name = PARAM_PROXIMITY_BASE_DIST, mandatory = false, defaultValue = "2")
	protected int proximityBaseDist;
	public static final String PARAM_PROXIMITY_BASE_FACTOR = "proximity-base-factor";
	@ConfigurationParameter(name = PARAM_PROXIMITY_BASE_FACTOR, mandatory = false, defaultValue = "3")
	protected int proximityBaseFactor;

	/** Search full text of articles in addition to their titles. */
	public static final String PARAM_SEARCH_FULL_TEXT = "search-full-text";
	@ConfigurationParameter(name = PARAM_SEARCH_FULL_TEXT, mandatory = false, defaultValue = "true")
	protected boolean searchFullText;

	/** Make all clues required to be present. */
	public static final String PARAM_CLUES_ALL_REQUIRED = "clues-all-required";
	@ConfigurationParameter(name = PARAM_CLUES_ALL_REQUIRED, mandatory = false, defaultValue = "true")
	protected boolean cluesAllRequired;

	/** Origin field of ResultInfo. This can be used to fetch different
	 * ResultInfos in different CAS flow branches. */
	public static final String PARAM_RESULT_INFO_ORIGIN = "result-info-origin";
	@ConfigurationParameter(name = PARAM_RESULT_INFO_ORIGIN, mandatory = false, defaultValue = "cz.brmlab.yodaqa.pipeline.solrfull.SolrFullPrimarySearch")
	protected String resultInfoOrigin;

	protected SolrQuerySettings settings = null;
	protected String srcName;
	protected Solr solr;

	protected JCas questionView;


	protected List<BingResult> results;
	protected int i;
	private String apikey;

	private boolean skip;
	private BingResultsCache cache;

	public static class BingResult {
		public String title;
		public String description;
		public int rank;

		public BingResult(String title, String description, int rank) {
			this.title = title;
			this.description = description;
			this.rank = rank;
		}
	}

	@Override
	public void initialize(UimaContext aContext) throws ResourceInitializationException {
		super.initialize(aContext);

		skip = false;

		cache = new BingResultsCache();
		Properties prop = new Properties();
		try {
			prop.load(getClass().getResourceAsStream("bingapi.properties"));
			apikey = (String)prop.get("apikey");
			if (apikey == null) throw new NullPointerException("Api key is null");
		} catch (IOException | NullPointerException e) {
			logger.info("No api key for bing api!");
			skip = true;
		}
	}

	@Override
	public void process(JCas jcas) throws AnalysisEngineProcessException {
		/* First, set up the views. */
		try {
			questionView = jcas.getView(CAS.NAME_DEFAULT_SOFA);
		} catch (Exception e) {
			throw new AnalysisEngineProcessException(e);
		}

		results = new ArrayList<>();
		i = 0;

		/* Run a search for concept clues (pageID)
		 * if they weren't included above. */

		Collection<ClueConcept> concepts;

		/* Run a search for text clues. */

		try {
			Collection<Clue> clues = JCasUtil.select(questionView, Clue.class);
			results = bingSearch(clues, hitListSize);
		} catch (Exception e) {
			throw new AnalysisEngineProcessException(e);
		}
	}

	private List<BingResult> bingSearch(Collection<Clue> clues, int hitListSize) {
		ArrayList<BingResult> res;
		StringBuilder sb = new StringBuilder();
//		int numOfResults = hitListSize;
		int numOfResults = 30;
		for (Clue c: clues) {
			sb.append(c.getLabel()).append(" ");
		}
		sb.deleteCharAt(sb.length() - 1);
		logger.info("QUERY: " + sb.toString());

		res = cache.load(sb.toString());
		if (res != null && res.size() > 0 || skip) return res;

		String query;
		String bingUrl = "";
//		final String bingUrlPattern = "https://api.datamarket.azure.com/Bing/Search/Web?Query=%%27%s%%27&$top=%d&$format=JSON&$Market=en-US";
		try {
			query = URLEncoder.encode(sb.toString(), Charset.defaultCharset().name());
			bingUrl = "https://api.datamarket.azure.com/Bing/Search/Web?"
					+ "Query=%27" + query + "%27&$top=" + numOfResults + "&$format=JSON&Market=%27en-US%27";//&Market=%27en-US%27


			final String accountKeyEnc = Base64.encodeBase64String((apikey + ":" + apikey).getBytes());

			final URL url = new URL(bingUrl);
			final URLConnection connection = url.openConnection();
			connection.setRequestProperty("Authorization", "Basic " + accountKeyEnc);

			GsonBuilder builder = new GsonBuilder();
			Map<String, Map> json = builder.create()
					.fromJson(new InputStreamReader(connection.getInputStream()), Map.class);
			Map<String, ArrayList> d = json.get("d");
			ArrayList<Map> results = d.get("results");
			int rank = 1;
			for (Map<String, String> m : results) {
				res.add(new BingResult(m.get("Title"), m.get("Description"), rank++));
			}
			cache.save(sb.toString(), res);
		} catch (IOException e) {
			logger.error("Unable to obtain bing results: " + e.getMessage());
			logger.info("BINGURL " + bingUrl);
			return res;
		}
		if (res.size() == 0) logger.info("No bing results.");
		return res;
	}

	@Override
	public boolean hasNext() throws AnalysisEngineProcessException {
		return i < results.size() || i == 0;
	}

	@Override
	public AbstractCas next() throws AnalysisEngineProcessException {
		BingResult result = i < results.size() ? results.get(i) : null;
		i++;

		JCas jcas = getEmptyJCas();
		try {
			jcas.createView("Question");
			CasCopier qcopier = new CasCopier(questionView.getCas(), jcas.getView("Question").getCas());
			copyQuestion(qcopier, questionView, jcas.getView("Question"));

			jcas.createView("Result");
			JCas resultView = jcas.getView("Result");
			if (result != null) {
				boolean isLast = (i == results.size());
				ResultInfo ri = generateBingResult(questionView, resultView, result, isLast ? i : 0);
				String title = ri.getDocumentTitle();
				logger.info(" ** SearchResultCAS: " + ri.getDocumentId() + " " + (title != null ? title : ""));
				/* XXX: Ugh. We clearly need global result ids. */
//				QuestionDashboard.getInstance().get(questionView).setSourceState(
//						"bing-fulltext",
//						Integer.parseInt(ri.getDocumentId()),
//						1);
			} else {
				/* We will just generate a single dummy CAS
				 * to avoid flow breakage. */
				resultView.setDocumentText("");
				resultView.setDocumentLanguage(questionView.getDocumentLanguage());
				ResultInfo ri = new ResultInfo(resultView);
				ri.setDocumentTitle("");
				ri.setOrigin(resultInfoOrigin);
				ri.setIsLast(i);
				ri.addToIndexes();
			}
		} catch (Exception e) {
			jcas.release();
			throw new AnalysisEngineProcessException(e);
		}
		return jcas;
	}

	protected void copyQuestion(CasCopier copier, JCas src, JCas jcas) throws Exception {
		copier.copyCasView(src.getCas(), jcas.getCas(), true);
	}

	protected ResultInfo generateBingResult(JCas questionView, JCas resultView,
											BingResult result,
											int isLast)
			throws AnalysisEngineProcessException {

//		Integer id = (Integer) result.doc.getFieldValue("id");
//		String title = (String) result.doc.getFieldValue("titleText");
//		double score = ((Float) result.doc.getFieldValue("score")).floatValue();


		// System.err.println("--8<-- " + text + " --8<--");
		resultView.setDocumentText(result.description); //Title or decription
		resultView.setDocumentLanguage("en"); // XXX

		AnswerFV afv = new AnswerFV();
		afv.setFeature(AF_ResultRR.class, 1 / ((float) result.rank));

		ResultInfo ri = new ResultInfo(resultView);
		ri.setDocumentId("1"); //FIXME dummy id
		ri.setDocumentTitle(result.title);
		ri.setSource(srcName);
//		ri.setRelevance(score);
		ri.setOrigin(resultInfoOrigin);
		ri.setAnsfeatures(afv.toFSArray(resultView));
		ri.setIsLast(isLast);
		ri.addToIndexes();
		return ri;
	}

	@Override
	public int getCasInstancesRequired() {
		return MultiThreadASB.maxJobs * 2;
	}
}