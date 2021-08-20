/**
 * This class calculates and displays various effectiveness measures including
 * average precision, precision at rank 10, NDCG at rank 10 and rank 1000 and
 * time-biased gain.
 * 
 * @author ryanhow
 *
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Evaluation {
	
	private static final Logger LOGGER = Logger.getLogger(Evaluation.class.getName());
	
	public static int QREL_TOPIC_ID_INDEX = 0;
	public static int QREL_DOCNO_INDEX = 2;
	public static int QREL_JUDGMENT_INDEX = 3;
	
	public static int RESULT_TOPIC_ID_INDEX = 0;
	public static int RESULT_Q0_INDEX = 1;
	public static int RESULT_DOCNO_INDEX = 2;
	public static int RESULT_RANK_INDEX = 3;
	public static int RESULT_SCORE_INDEX = 4;
	public static int RESULT_RUN_TAG_INDEX = 5;
	
	public static String METADATA_FILE_NAME = "metadata.txt";
	public static int METADATA_DOCNO_INDEX = 0;
	public static int METADATA_DOC_LENGTH_INDEX = 3;
	
	public static double P_CLICK_RELEVANT_SUMMARY = 0.64;
	public static double P_CLICK_NON_RELEVANT_SUMMARY = 0.39;
	public static double P_SAVE_GIVEN_RELEVANT = 0.77;
	public static double P_SAVE_GIVEN_NON_RELEVANT = 0.27;
	public static double TIME_TO_EVAL_SUMMARY = 4.4;
	public static double HALF_LIFE = 224;
	
	public static String Q0 = "Q0";
	
	public static int DOCNO_LENGTH = 13;
	
	public static void main(String[] args) throws ClassNotFoundException, IOException {
		
		if(args.length != 3) {
			LOGGER.log(Level.SEVERE, "Invalid input. Usage: 'java " + Evaluation.class.getName() + " <PATH_TO_INDEX_DIRECTORY> <PATH_TO_QRELS_FILE> <PATH_TO_RESULT_FILE>'");
			return;
		}
		
		//Check existence of index directory
		Path pathToIndexDirectory = Paths.get(args[0]);
		if (!Files.exists(pathToIndexDirectory)) {
			LOGGER.log(Level.SEVERE, "Directory '" + args[0] + "' does not exist.");
			return;
		}
		
		//Check existence of qrels file
		Path pathToQrelsFile = Paths.get(args[1]);
		if(!Files.exists(pathToQrelsFile)) {
			LOGGER.log(Level.SEVERE, "File '" + args[1] + "' does not exist.");
			return;
		}
 		
		//Check existence of result file
		Path pathToResultFile = Paths.get(args[2]);
		if(!Files.exists(pathToResultFile)) {
			LOGGER.log(Level.SEVERE, "File '" + args[2] + "' does not exist.");
			return;
		}
		
		//Formatting
		StringBuilder pathToMetadataFile = new StringBuilder();
		pathToMetadataFile.append(args[0]);
		if(pathToMetadataFile.charAt(pathToMetadataFile.length() - 1) != '/') {
			pathToMetadataFile.append("/");
		}
		pathToMetadataFile.append(METADATA_FILE_NAME);
		
		Map<Integer, List<String>> metadataMap = getMetadataMap(pathToMetadataFile.toString()); //Map to go from internalId->{docNo, headline, date, length}
		
		Map<String, Integer> docNoToInternalIdMap = constructDocNoToInternalIdMap(metadataMap); //Map to go from docNo->internalId
		
		Map<Integer, Set<String>> topicToRelevantDocs = getTopicToRelevantDocs(args[1]); //Map to go from topicId -> {set of relevant docNos}		
		
		File file = new File(args[2]);
		Scanner scanner = new Scanner(file);
		
		int topicId = -1; 
		List<Result> resultsForTopicId = new ArrayList<>(); //Holds a list of the result objects (topicId, docNo) for a specific topic
		
		Map<Integer, Double> topicIdToAveragePrecision = new HashMap<>(); //Holds mapping between topicId and average precision
		Map<Integer, Double> topicIdToPrecisionAt10 = new HashMap<>(); //Holds mapping between topicId and precision @ 10 
		Map<Integer, Double> topicIdToNdcgAt10 = new HashMap<>(); //Holds mapping between topicId and ndcg @ 10 
		Map<Integer, Double> topicIdToNdcgAt1000 = new HashMap<>(); //Holds mapping between topicId and ndcg @ 1000 
		Map<Integer, Double> topicIdToTbg = new HashMap<>(); //Holds mapping between topicId and tbg 
			
		while(scanner.hasNextLine()) { 
			String[] values = scanner.nextLine().split(" "); 
			if(values.length != 6) { //Invalid result file format
				LOGGER.log(Level.SEVERE, "Invalid result file format.");
				return; 
			}
			
			//The following statements also check for correct result file format
			int currentTopicId;
			try {
				currentTopicId = Integer.parseInt(values[RESULT_TOPIC_ID_INDEX]);
			} catch(Exception e) {
				LOGGER.log(Level.SEVERE, "Invalid result file format. Cannot parse '" + values[RESULT_TOPIC_ID_INDEX] + "' to integer topicId.");
				return;
			}
			
			String q0 = values[RESULT_Q0_INDEX];
			if(!q0.equals(Q0)) {
				LOGGER.log(Level.SEVERE, "Invalid result file format. '" + q0 + "' should be " + Q0);
				return;
			}
			
			String docNo = values[RESULT_DOCNO_INDEX];
			if(docNo.length() != DOCNO_LENGTH) {
				LOGGER.log(Level.SEVERE, "Invalid result file format. '" + docNo + "' is an invalid docNo.");
				return;
			}
			
			int rank;
			try {
				rank = Integer.parseInt(values[RESULT_RANK_INDEX]);
			} catch(Exception e) {
				LOGGER.log(Level.SEVERE, "Invalid result file format. Cannot parse '" + values[RESULT_RANK_INDEX] + "' to integer rank.");
				return;
			}
			
			double score;
			try {
				score = Double.parseDouble(values[RESULT_SCORE_INDEX]);
			} catch(Exception e) {
				LOGGER.log(Level.SEVERE, "Invalid result file format. Cannot parse '" + values[RESULT_SCORE_INDEX] + "' to double score.");
				return;
			}
			
			String runTag = values[RESULT_RUN_TAG_INDEX];
			if(runTag.length() < 1) {
				LOGGER.log(Level.SEVERE, "Invalid result file format. runTag value must be atleast 1 character long.");
				return;
			}
			
			if(topicId == -1) { //Initially topicId will be -1
				topicId = currentTopicId;
			}
			
			if(currentTopicId != topicId || !scanner.hasNextLine()) { //End of topic or end of file (EOF needed or else last topic will not be captured)
				if(topicToRelevantDocs.containsKey(topicId)) { //Only for topics in qrel, skip over extra topics
					Result[] results = resultsForTopicId.toArray(new Result[resultsForTopicId.size()]);
					Arrays.sort(results); //Sort list by descending score
					
					double averagePrecision = getAveragePrecision(topicId, results, topicToRelevantDocs);
					double precisionAt10 = getPrecisionAt10(topicId, results, topicToRelevantDocs);
					double ndcgAt10 = getNdcgAt10(topicId, results, topicToRelevantDocs);
					double ndcgAt1000 = getNdcgAt1000(topicId, results, topicToRelevantDocs);
					double tbg = getTbg(topicId, results, topicToRelevantDocs, metadataMap, docNoToInternalIdMap);
					
					topicIdToAveragePrecision.put(topicId, averagePrecision);
					topicIdToPrecisionAt10.put(topicId, precisionAt10);
					topicIdToNdcgAt10.put(topicId, ndcgAt10);
					topicIdToNdcgAt1000.put(topicId, ndcgAt1000);
					topicIdToTbg.put(topicId, tbg);
				}
					
				topicId = currentTopicId;
				resultsForTopicId.clear(); 
			}
			
			Result result = new Result(docNo, score);
			resultsForTopicId.add(result);
		}
		
		//Outputting results
		System.out.format("%-10s%-20s%-10s%-10s%-15s%-10s%n", "Topic ID", "Average Precision", "P@10", "NDCG@10", "NDCG@1000", "TBG");
		for(int i = 401; i <= 450; i++) {
			if(i == 416 || i == 423 || i == 437 || i == 444 || i == 447) {
				continue;
			}
			
			//The following assignment statements use the ternary operator to ensure that if there are no results for a given topic, that the measures associated with that topic are 0 as per instructions
			double averagePrecision = topicIdToAveragePrecision.containsKey(i) ? topicIdToAveragePrecision.get(i) : 0; 
			double precisionAt10 = topicIdToPrecisionAt10.containsKey(i) ? topicIdToPrecisionAt10.get(i) : 0;
			double ndcgAt10 = topicIdToNdcgAt10.containsKey(i) ? topicIdToNdcgAt10.get(i) : 0;
			double ndcgAt1000 = topicIdToNdcgAt1000.containsKey(i) ? topicIdToNdcgAt1000.get(i) : 0;
			double tbg = topicIdToTbg.containsKey(i) ? topicIdToTbg.get(i) : 0;
			
			System.out.format("%-10d%-20f%-10f%-10f%-15f%-10f%n", i, averagePrecision, precisionAt10, ndcgAt10, ndcgAt1000, tbg);
		}
		
	}
	
	/**
	 * This method attempts to deserialize and fetch the metadata map on disk.
	 * 
	 * @param metadataMapFilePath
	 * @return the metadata map or null on error
	 */
	private static Map<Integer, List<String>> getMetadataMap(String metadataMapFilePath){
		if(metadataMapFilePath == null || metadataMapFilePath.length() == 0) {
			LOGGER.log(Level.SEVERE, "metadataMapFilePath provided to getMetadataMap was null or empty.");
			return null;
		}
		Map<Integer, List<String>> metadataMap = null;
		FileInputStream fis;
		ObjectInputStream ois;
		
		try {
			fis = new FileInputStream(metadataMapFilePath);
			ois = new ObjectInputStream(fis);
			metadataMap = (HashMap<Integer, List<String>>) ois.readObject();
			ois.close();
			fis.close();
		} catch(Exception e) {
			LOGGER.log(Level.SEVERE, "Error getting metadata map from disk. " + e.getMessage());
		}
		return metadataMap;
	}
	
	/**
	 * This method constructs the map from docno to internal id.
	 * 
	 * @param metadataMap the map from internal id to metadata
	 * @return the map from docno to internal id or null on error
	 */
	private static Map<String, Integer> constructDocNoToInternalIdMap(Map<Integer, List<String>> metadataMap) {
		if(metadataMap == null || metadataMap.size() == 0) {
			LOGGER.log(Level.SEVERE, "metadataMap provided to constructDocNoToInternalIdMap was null or empty.");
			return null;
		}
		Map<String, Integer> docNoToInternalIdMap = new HashMap<>();
		for(int internalId : metadataMap.keySet()) {
			String docNo = metadataMap.get(internalId).get(METADATA_DOCNO_INDEX);
			docNoToInternalIdMap.put(docNo, internalId);
		}
		return docNoToInternalIdMap;
	}
	
	/**
	 * This method obtains the map from the topic id to the set of relevant
	 * documents for that topic.
	 * 
	 * @param pathToQrelsFile the path to the qrels file
	 * @return the map from topic id to the set of relevant documents or null on
	 *         error
	 */
	private static Map<Integer, Set<String>> getTopicToRelevantDocs(String pathToQrelsFile) throws FileNotFoundException {
		if(pathToQrelsFile == null || pathToQrelsFile.length() == 0) {
			LOGGER.log(Level.SEVERE, "pathToQrelsFile file was null or empty.");
			return null;
		}
		
		Map<Integer, Set<String>> topicToRelevantDocs = new HashMap<>();
		
		File file = new File(pathToQrelsFile);
		Scanner scanner = new Scanner(file);
		while(scanner.hasNextLine()) {
			Set<String> relevantDocs;
			String[] values = scanner.nextLine().split(" "); 
			
			int topicId = Integer.parseInt(values[QREL_TOPIC_ID_INDEX]);
			String docNo = values[QREL_DOCNO_INDEX];
			int judgment = Integer.parseInt(values[QREL_JUDGMENT_INDEX]);
			
			if(judgment > 0) { //Document is relevant for the topicId
				if(topicToRelevantDocs.containsKey(topicId)) {
					relevantDocs = topicToRelevantDocs.get(topicId);
				} else { 
					relevantDocs = new HashSet<>();
				}
				relevantDocs.add(docNo);
				topicToRelevantDocs.put(topicId, relevantDocs);
			}
		}
		return topicToRelevantDocs;
	}
	
	/**
	 * This method calculates the average precision.
	 * 
	 * @param topicId             the topic id for the query
	 * @param results             the array of results for the topic
	 * @param topicToRelevantDocs the map from the topic to the set of relevant
	 *                            documents
	 * @return the average precision or -1.0 on error
	 */
	private static double getAveragePrecision(int topicId, Result[] results, Map<Integer, Set<String>> topicToRelevantDocs) {
		if(topicId < 0 || results == null || results.length == 0 || topicToRelevantDocs == null || topicToRelevantDocs.size() == 0) {
			LOGGER.log(Level.SEVERE, "Invalid input provided to getAveragePrecision.");
			return -1.0;
		}
				
		Set<String> relevantDocNos = topicToRelevantDocs.get(topicId);
		
		int relevantCountAtN = 0;
		double sumOfPrecisionAtN = 0;
		for(int i = 1; i <= results.length; i++) {
			boolean relevant = relevantDocNos.contains(results[i - 1].getDocNo());
			relevantCountAtN += relevant ? 1 : 0;
			double precisionAtN = (double) relevantCountAtN / i;
			sumOfPrecisionAtN += relevant ? precisionAtN : 0;
		}
		
		return sumOfPrecisionAtN / relevantDocNos.size();
	}
	
	/**
	 * Thie method calculates the precision at rank 10.
	 * 
	 * @param topicId             the topic id for the query
	 * @param results             the array of results for the topic
	 * @param topicToRelevantDocs the map from the topic to the set of relevant
	 *                            documents
	 * @return the precision at rank 10 or -1.0 on error
	 */
	private static double getPrecisionAt10(int topicId, Result[] results, Map<Integer, Set<String>> topicToRelevantDocs) {
		if(topicId < 0 || results == null || results.length == 0 || topicToRelevantDocs == null || topicToRelevantDocs.size() == 0) {
			LOGGER.log(Level.SEVERE, "Invalid input provided to getPrecisionAt10.");
			return -1.0;
		}
		
		Set<String> relevantDocNos = topicToRelevantDocs.get(topicId);
		
		int relevantCountAtN = 0;
		for(int i = 1; i <= 10; i++) {
			if(i > results.length) { //Handle cases where number of results is less than 10
				break;
			}
			boolean relevant = relevantDocNos.contains(results[i - 1].getDocNo());
			relevantCountAtN += relevant ? 1 : 0;
		}
		
		return (double) relevantCountAtN / 10;
	}

	/**
	 * This method calculates the NDCG at rank 10.
	 * 
	 * @param topicId             the topic id for the query
	 * @param results             the array of results for the topic
	 * @param topicToRelevantDocs the map from the topic to the set of relevant
	 *                            documents
	 * @return the NDCG at rank 10 or -1.0 on error
	 */
	private static double getNdcgAt10(int topicId, Result[] results, Map<Integer, Set<String>> topicToRelevantDocs) {
		if(topicId < 0 || results == null || results.length == 0 || topicToRelevantDocs == null || topicToRelevantDocs.size() == 0) {
			LOGGER.log(Level.SEVERE, "Invalid input provided to getNdcgAt10.");
			return -1.0;
		}
		
		Set<String> relevantDocNos = topicToRelevantDocs.get(topicId);
		
		double dcgAt10 = 0;
		for(int i = 1; i <= 10; i++) {
			if(i > results.length) { //Handle cases where number of results is less than 10
				break;
			}
			boolean relevant = relevantDocNos.contains(results[i - 1].getDocNo());
			dcgAt10 += relevant ? 1 / (log2(i + 1)) : 0;
		}
		
		double idcgAt10 = getIdcgAtN(10, relevantDocNos);
		return dcgAt10 / idcgAt10;	
	}
	
	/**
	 * This method performs a log operation with base 2.
	 * 
	 * @param x the argument for the log calculation
	 * @return the answer
	 */
	private static double log2(double x) {
		return Math.log(x) / Math.log(2);
	}
	
	/**
	 * This method calculates the IDCG at rank N.
	 * 
	 * @param n              the rank to calculate IDCG at
	 * @param relevantDocNos the set of relevant documents
	 * @return the IDCG at rank N or -1.0 on error
	 */
	private static double getIdcgAtN(int n, Set<String> relevantDocNos) {
		if(n < 0 || relevantDocNos == null || relevantDocNos.size() == 0) {
			LOGGER.log(Level.SEVERE, "Invalid input provided to getIdcgAtN.");
			return -1.0;
		}
		double idcgAtN = 0;
		int nCounter = 1;
		int numOfRelevantDocs = relevantDocNos.size();
		while(numOfRelevantDocs > 0) {
			idcgAtN += 1 / log2(nCounter + 1);
			if(nCounter == n) {
				break;
			}
			nCounter++;
			numOfRelevantDocs--;
		}
		return idcgAtN;
	}

	/**
	 * This method calculates NDCG at rank 1000.
	 * 
	 * @param topicId             the topic id for the query
	 * @param results             the array of results for the topic
	 * @param topicToRelevantDocs the map from topic to the set of relevant document
	 * @return the NDCG at rank 1000 or -1.0 on error
	 */
	private static double getNdcgAt1000(int topicId, Result[] results, Map<Integer, Set<String>> topicToRelevantDocs) {
		if(topicId < 0 || results == null || results.length == 0 || topicToRelevantDocs == null || topicToRelevantDocs.size() == 0) {
			LOGGER.log(Level.SEVERE, "Invalid input provided to getNdcgAt1000.");
			return -1.0;
		}
		
		Set<String> relevantDocNos = topicToRelevantDocs.get(topicId);
		
		double dcgAt1000 = 0;
		for(int i = 1; i <= 1000; i++) {
			if(i > results.length) { //Handle cases where number of results is less than 1000
				break;
			}
			boolean relevant = relevantDocNos.contains(results[i - 1].getDocNo());
			dcgAt1000 += relevant ? 1 / (log2(i + 1)) : 0;
		}
		
		double idcgAt1000 = getIdcgAtN(1000, relevantDocNos);
		return dcgAt1000 / idcgAt1000;
	}

	/**
	 * This method calculates the time-biased gain.
	 * 
	 * @param topicId              the topic id for the query
	 * @param results              the array of results for the topic
	 * @param topicToRelevantDocs  the map from topic to the set of relevant documents
	 * @param metadataMap          the map from internalId of the document to metadata
	 * @param docNoToInternalIdMap the map from docno to internalId of the document
	 * @return the time-biased gain or -1.0 on error
	 */
	private static double getTbg(int topicId, Result[] results, Map<Integer, Set<String>> topicToRelevantDocs, Map<Integer, List<String>> metadataMap, Map<String, Integer> docNoToInternalIdMap) {
		if(topicId < 0 || results == null || results.length == 0 || topicToRelevantDocs == null || topicToRelevantDocs.size() == 0 || metadataMap == null || metadataMap.size() == 0 || docNoToInternalIdMap == null || docNoToInternalIdMap.size() == 0) {
			LOGGER.log(Level.SEVERE, "Invalid input provided to getTbg.");
			return -1.0;
		}
		
		Set<String> relevantDocNos = topicToRelevantDocs.get(topicId);
		
		double tbg = 0;
		for(int i = 1; i <= results.length; i++) {
			if(relevantDocNos.contains(results[i - 1].getDocNo())) { //Non relevant docs have a gain of 0 & therefore nothing to add to tbg
				double gain = 1 * P_CLICK_RELEVANT_SUMMARY * P_SAVE_GIVEN_RELEVANT;
				double tk = getTk(i, results, relevantDocNos, metadataMap, docNoToInternalIdMap);
				double dt = Math.exp((-tk * Math.log(2)) / HALF_LIFE);
				tbg += gain * dt;
			}
		}
		return tbg;
	}
	
	/**
	 * This method calculates the value for Tk.
	 * 
	 * @param k                    the value of k
	 * @param results              the array of results for the topic
	 * @param relevantDocNos       the set of relevant documents for the topic
	 * @param metadataMap          the map from internalId of the document to the metadata
	 * @param docNoToInternalIdMap the map from docno to the internalId of the document
	 * @return the Tk value or -1.0 on error
	 */
	private static double getTk(int k, Result[] results, Set<String> relevantDocNos, Map<Integer, List<String>> metadataMap, Map<String, Integer> docNoToInternalIdMap) {
		if(k < 0 || results == null || results.length == 0 || relevantDocNos == null || relevantDocNos.size() == 0 || metadataMap == null || metadataMap.size() == 0 || docNoToInternalIdMap == null || docNoToInternalIdMap.size() == 0) {
			LOGGER.log(Level.SEVERE, "Invalid input provided to getTk.");
			return -1.0;
		}
		double tk = 0;
		for(int i = 1; i < k; i++) {
			String docNo = results[i - 1].getDocNo();
			int docLength = getDocLength(docNo, metadataMap, docNoToInternalIdMap);
			boolean relevant = relevantDocNos.contains(docNo);
			tk += relevant ? TIME_TO_EVAL_SUMMARY + (((0.018 * docLength) + 7.8) * P_CLICK_RELEVANT_SUMMARY) : TIME_TO_EVAL_SUMMARY + (((0.018 * docLength) + 7.8) * P_CLICK_NON_RELEVANT_SUMMARY);
		}
		return tk;
	}
	
	/**
	 * This method obtains the document length
	 * 
	 * @param docNo                the docno of the document
	 * @param metadataMap          the map from internalId of the document to the metadata
	 * @param docNoToInternalIdMap the map from docno to internalId of the document
	 * @return the document length or -1 on error
	 */
	private static int getDocLength(String docNo, Map<Integer, List<String>> metadataMap, Map<String, Integer> docNoToInternalIdMap) {
		if(docNo == null || docNo.length() == 0 || metadataMap == null || metadataMap.size() == 0 || docNoToInternalIdMap == null || docNoToInternalIdMap.size() == 0) {
			LOGGER.log(Level.SEVERE, "Invalid input provided to getDocLength.");
			return -1;
		}
		int internalId = docNoToInternalIdMap.get(docNo);
		return Integer.parseInt(metadataMap.get(internalId).get(METADATA_DOC_LENGTH_INDEX));
	}

}