/**
 * This class performs BM25 retrieval given a set of query topics and queries.
 * The results from retrieval are written to a specified file.
 * 
 * @author ryanhow
 *
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BM25 {

	private static Logger LOGGER = Logger.getLogger(BM25.class.getName());
	public static String METADATA_FILE_NAME = "metadata.txt";
	public static String LEXICON_FILE_NAME = "lexicon.txt";
	public static String INVERTED_INDEX_FILE_NAME = "invertedIndex.txt";
	
	public static int METADATA_DOCNO_INDEX = 0;
	public static int METADATA_DOC_LENGTH_INDEX = 3;
	
	public static double k1 = 1.2;
	public static double b = 0.75;
	public static double k2 = 7;

	public static void main(String[] args) throws FileNotFoundException {

		if (args.length != 3) {
			LOGGER.log(Level.SEVERE, "Invalid input. Usage: 'java" + BM25.class.getName() + " <PATH_TO_INDEX_DIRECTORY> <PATH_TO_QUERIES_FILE> <OUTPUT_FILE_NAME>'");
			return;
		}

		//Check existence of index directory
		Path pathToIndexDirectory = Paths.get(args[0]);
		if (!Files.exists(pathToIndexDirectory)) {
			LOGGER.log(Level.SEVERE, "Directory '" + args[0] + "' does not exist.");
			return;
		}

		//Check existence of query file
		Path pathToQueriesFile = Paths.get(args[1]);
		if (!Files.exists(pathToQueriesFile)) {
			LOGGER.log(Level.SEVERE, "File '" + args[1] + "' does not exist.");
			return;
		}
		
		//Check existence of output file
		Path outputFileName = Paths.get(args[2]);
		if(Files.exists(outputFileName)) { 
			LOGGER.log(Level.SEVERE, "File '" + args[2] + "' already exists. Please provide a file which does not already exist.");
			return;
		}

		//Formatting
		StringBuffer formattedPathToIndexDirectory = new StringBuffer();
		formattedPathToIndexDirectory.append(args[0]);
		if (formattedPathToIndexDirectory.charAt(formattedPathToIndexDirectory.length() - 1) != '/') {
			formattedPathToIndexDirectory.append("/");
		}
		
		//Retrieve auxiliary maps
		StringBuffer metadataMapFilePath = new StringBuffer();
		metadataMapFilePath.append(formattedPathToIndexDirectory.toString());
		metadataMapFilePath.append(METADATA_FILE_NAME);
				
		Map<Integer, List<String>> metadataMap = getMap(metadataMapFilePath.toString()); //Map to go from internalId->{docNo, headline, date, length}
		
		double averageDocLength = getAverageDocLength(metadataMap);
		int numDocs = metadataMap.size(); //The number of documents in the collection
				
		StringBuffer lexiconFilePath = new StringBuffer();
		lexiconFilePath.append(formattedPathToIndexDirectory.toString());
		lexiconFilePath.append(LEXICON_FILE_NAME);
				
		Map<String, Integer> lexicon = getMap(lexiconFilePath.toString()); //Map to go from token string -> token id
				
		StringBuffer invertedIndexFilePath = new StringBuffer();
		invertedIndexFilePath.append(formattedPathToIndexDirectory.toString());
		invertedIndexFilePath.append(INVERTED_INDEX_FILE_NAME);
				
		Map<Integer, List<Integer>> invertedIndex = getMap(invertedIndexFilePath.toString()); //Map to go from token id -> {internalId, count, internalId, count,...}
		
		LOGGER.log(Level.INFO, "Indexing complete. All auxiliary data structures loaded into RAM.");
		
		//Read queries file and construct results
		File file = new File(args[1]);
		Scanner scanner = new Scanner(file);
		
		StringBuffer results = new StringBuffer(); //Hold all results
		while(scanner.hasNextLine()) {
			String rawTopicId = scanner.nextLine().trim(); //First line is the topic id 
			int topicId = Integer.parseInt(rawTopicId); 
			
			String rawQuery = scanner.nextLine().trim(); //Second line is the actual query
			List<String> tokens = tokenize(rawQuery); 
			tokens = stem(tokens); 
			
			Map<Integer, Integer> tokenCounts = countTokens(tokens, lexicon); //Obtain a mapping from the tokenId to the token count for the query
			
			List<Integer> tokenIds = getTokenIdsFromTokens(tokens, lexicon); //Obtain unique tokenIds from query 
			
			Map<Integer, Double> accumulatorMap = performBM25(tokenIds, metadataMap, invertedIndex, tokenCounts, averageDocLength, numDocs);
			
			AccumulatorEntry[] sortedAccumulatorEntries = getSortedAccumulatorEntries(accumulatorMap);
			
			boolean addToResultsSuccess = addToResults(results, sortedAccumulatorEntries, topicId, metadataMap);
			if(!addToResultsSuccess) {
				LOGGER.log(Level.SEVERE, "Error adding results for topic " + topicId);
				return;
			}
			
		}
		boolean writeResultsSuccess = writeResults(results.toString(), args[2]);
		if(!writeResultsSuccess) {
			LOGGER.log(Level.SEVERE, "Error writing results to file.");
			return;
		}
		
	}
	
	/**
	 * This method attempts to deserialize and fetch the auxiliary maps stored on
	 * disk.
	 * 
	 * @param filePath the path of the file holding the map
	 * @return the map or null on error
	 */
	private static Map getMap(String filePath){
		Map map = null;
		FileInputStream fis;
		ObjectInputStream ois;
		
		try {
			fis = new FileInputStream(filePath);
			ois = new ObjectInputStream(fis);
			map = (Map) ois.readObject();
			ois.close();
			fis.close();
		} catch(Exception e) {
			LOGGER.log(Level.SEVERE, "Error getting map from disk.");
		}
		return map;
	}
	
	
	/**
	 * This method computes the average document length for the collection.
	 * 
	 * @param metadataMap the map from internal id to metadata (docno, headline, date, length)
	 * @return the average document length or -1.0 on error
	 */
	private static double getAverageDocLength(Map<Integer, List<String>> metadataMap) {
		if(metadataMap == null || metadataMap.size() == 0) {
			LOGGER.log(Level.SEVERE, "metadata map provided to getAverageDocLength was null or empty.");
			return -1.0;
		}
		double totalDocLengths = 0;
		for(List<String> metadata : metadataMap.values()) {
			totalDocLengths += Double.parseDouble(metadata.get(METADATA_DOC_LENGTH_INDEX));
		}
		return totalDocLengths / metadataMap.size();
	}
	
	/**
	 * This method applies a tokenization scheme (see below) on the provided text.
	 * 1. Downcase text 2. Split on non-alphanumerics
	 * 
	 * @param text the text content of the document
	 * @return the list of string tokens or null on error
	 */
	private static List<String> tokenize(String text) {
		if (text == null || text.length() == 0) {
			LOGGER.log(Level.SEVERE, "text provided to tokenize was null or empty.");
			return null;
		}

		List<String> tokens = new ArrayList<>();

		text = text.toLowerCase();
		int start = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (!Character.isLetterOrDigit(c)) {
				if (start != i) {
					String token = text.substring(start, i);
					tokens.add(token);
				}
				start = i + 1;
			}
		}
		if (start != text.length()) {
			tokens.add(text.substring(start, text.length()));
		}
		return tokens;
	}
	
	/**
	 * This method applies the Porter Stemmer on a list of tokens.
	 * 
	 * @param tokens the list of tokens to be stemmed
	 * @return the list of stemmed tokens or null on error
	 */
	private static List<String> stem(List<String> tokens) {
		if (tokens == null || tokens.size() == 0) {
			LOGGER.log(Level.SEVERE, "tokens provided to stem was null or empty.");
			return null;
		}
		List<String> stems = new ArrayList<>();
		for (String token : tokens) {
			stems.add(PorterStemmer.stem(token));
		}
		return stems;
	}
	
	/**
	 * This method counts the occurrences of each token in the query,
	 * 
	 * @param tokens  the list of tokens
	 * @param lexicon the map from token string to token id
	 * @return the token counts or null on error
	 */
	private static Map<Integer, Integer> countTokens(List<String> tokens, Map<String, Integer> lexicon) {
		if(tokens == null || tokens.size() == 0 || lexicon == null || lexicon.size() == 0) {
			LOGGER.log(Level.SEVERE, "Invalid input provided to countTokens.");
			return null;
		}
		Map<Integer, Integer> tokenCounts = new HashMap<>();
		for(String token : tokens) {
			if(lexicon.containsKey(token)) { //Only consider tokens in our lexicon
				int tokenId = lexicon.get(token);
				if(tokenCounts.containsKey(tokenId)) {
					tokenCounts.put(tokenId, tokenCounts.get(tokenId) + 1);
				} else {
					tokenCounts.put(tokenId, 1);
				}
			}
		}
		return tokenCounts;
	}
	
	/**
	 * This method obtains the unique tokenIds from the query.
	 * 
	 * @param tokens  the list of tokens
	 * @param lexicon the map from token string to token id
	 * @return the list of unque token ids or null on error
	 */
	private static List<Integer> getTokenIdsFromTokens(List<String> tokens, Map<String, Integer> lexicon) {
		if (tokens == null || tokens.size() == 0 || lexicon == null || lexicon.size() == 0) {
			LOGGER.log(Level.SEVERE, "Invalid input provided to getTokenIdsFromTokens.");
			return null;
		}
		List<Integer> tokenIds = new ArrayList<>();
		for (String token : tokens) {
			if (lexicon.containsKey(token)) { // Only consider tokens in our lexicon
				int tokenId = lexicon.get(token);
				if (!tokenIds.contains(tokenId)) { 
					tokenIds.add(tokenId);
				}
			}
		}
		return tokenIds;
	}
	
	/**
	 * This method performs the BM25 algorithm.
	 * 
	 * @param tokenIds         the list of token ids for the query
	 * @param metadataMap      the map from internal id to metadata
	 * @param invertedIndex    the map from token id to postings list (internalId, count,...)
	 * @param tokenCounts      the count of each token in the query
	 * @param averageDocLength the average document length for the collection
	 * @param numDocs          the number of documents in the collection
	 * @return the map from docId to BM25 score or null on error
	 */
	private static Map<Integer, Double> performBM25(List<Integer> tokenIds, Map<Integer, List<String>> metadataMap, Map<Integer, List<Integer>> invertedIndex, Map<Integer, Integer> tokenCounts, double averageDocLength, int numDocs) {
		if(tokenIds == null || tokenIds.size() == 0 || metadataMap == null || metadataMap.size() == 0 || invertedIndex == null || invertedIndex.size() == 0 || tokenCounts == null || tokenCounts.size() == 0 || averageDocLength < 0 || numDocs < 0) {
			LOGGER.log(Level.SEVERE, "Invalid input provided to performBM25");
			return null;
		}
		
		Map<Integer, Double> accumulator = new HashMap<Integer, Double>(); 
		for(int tokenId : tokenIds) { //Term-at-a-time approach
			int frequencyInQuery = tokenCounts.get(tokenId);
			
			List<Integer> postingsList = invertedIndex.get(tokenId); 
			int numberOfDocsContainingToken = postingsList.size() / 2; 
			
			int i = 0; 
			while(i < postingsList.size()) {
				int docId = postingsList.get(i); 
				int frequencyInDoc = postingsList.get(i + 1);
				int docLength = Integer.parseInt(metadataMap.get(docId).get(METADATA_DOC_LENGTH_INDEX)); 
				
				double BM25 = calculateBM25(frequencyInDoc, docLength, frequencyInQuery, numberOfDocsContainingToken, averageDocLength, numDocs);
				
				if(accumulator.containsKey(docId)) { 
					accumulator.put(docId, accumulator.get(docId) + BM25);
				} else { 
					accumulator.put(docId, BM25);
				}
				
				i += 2;
			}
		}
		
		return accumulator;
	}
	
	/**
	 * This method performs the actual BM25 calculation.
	 * 
	 * @param frequencyInDoc              the frequency of the token in the document
	 * @param docLength                   the document length
	 * @param frequencyInQuery            the frequency of the token in the query
	 * @param numberOfDocsContainingToken the number of documents in the collection containing the token
	 * @param averageDocLength            the average document length in the collection
	 * @param numDocs                     the number of documents in the collection
	 * @return the BM25 score
	 */
	private static double calculateBM25(int frequencyInDoc, int docLength, int frequencyInQuery, int numberOfDocsContainingToken, double averageDocLength, int numDocs) {
		double K = k1 * ((1 - b) + b  * (docLength / averageDocLength));
		return (((k1 + 1) * frequencyInDoc) / (K + frequencyInDoc)) * (((k2 + 1) * frequencyInQuery) / (k2 + frequencyInQuery)) * Math.log((numDocs - numberOfDocsContainingToken + 0.5) / (numberOfDocsContainingToken + 0.5));
	}
	
	/**
	 * This method obtains the sorted accumulator entries in descending order of
	 * BM25 score.
	 * 
	 * @param accumulatorMap the map from docId to BM25 score
	 * @return the sorted array of accumulator entries or null on error
	 */
	private static AccumulatorEntry[] getSortedAccumulatorEntries(Map<Integer, Double> accumulatorMap) {
		if(accumulatorMap == null || accumulatorMap.size() == 0) {
			LOGGER.log(Level.SEVERE, "accumulator map provided to getSortedAccumulatorEntries was null or empty.");
			return null;
		}
		List<AccumulatorEntry> accumulatorEntriesList = new ArrayList<>();
		for(int docId : accumulatorMap.keySet()) {
			AccumulatorEntry entry = new AccumulatorEntry(docId, accumulatorMap.get(docId));
			accumulatorEntriesList.add(entry);
		}
		AccumulatorEntry[] accumulatorEntries = new AccumulatorEntry[accumulatorEntriesList.size()];
		for(int i = 0; i < accumulatorEntries.length; i++) {
			accumulatorEntries[i] = accumulatorEntriesList.get(i);
		}
		Arrays.sort(accumulatorEntries);
		return accumulatorEntries;
	}
	
	/**
	 * This method adds the top 1000 (or less) results for the topic to the overall
	 * results.
	 * 
	 * @param results                  the overall results
	 * @param sortedAccumulatorEntries the array of accumulator entries sorted in descending order of BM25 score
	 * @param topicId                  the topic id for the topic
	 * @param metadataMap              the map from internal id to metadata
	 * @return true on success, false on error
	 */
	private static boolean addToResults(StringBuffer results, AccumulatorEntry[] sortedAccumulatorEntries, int topicId, Map<Integer, List<String>> metadataMap) {
		if(results == null || sortedAccumulatorEntries == null || sortedAccumulatorEntries.length == 0 || topicId < 0 || metadataMap == null || metadataMap.size() == 0) {
			LOGGER.log(Level.SEVERE, "Invalid input provided to addToResults");
			return false;
		}
		int resultCount = 0;
		for(int i = 0; i < sortedAccumulatorEntries.length; i++) {
			if(resultCount == 1000) { //Ensure only top 1000 are returned
				break;
			}
			results.append(topicId);
			results.append(" ");
			results.append("Q0");
			results.append(" ");
			results.append(metadataMap.get(sortedAccumulatorEntries[i].getDocId()).get(METADATA_DOCNO_INDEX));
			results.append(" ");
			results.append(i + 1);
			results.append(" ");
			results.append(sortedAccumulatorEntries[i].getScore());
			results.append(" ");
			results.append("BM25");
			results.append("\n");
			resultCount++;
		}
		return true;
	}
	
	/**
	 * This method writes the BM25 retrieval results to a file.
	 * 
	 * @param results  the BM25 retrieval results
	 * @param fileName the name of the file to be written to
	 * @return true on success, false on error
	 */
	private static boolean writeResults(String results, String fileName) {
		File f = new File(fileName);
		FileWriter fw;

		try {
			f.createNewFile(); 
			fw = new FileWriter(fileName);
			fw.write(results);
			fw.close();
		} catch (IOException e) {
			LOGGER.log(Level.SEVERE, "Error writing results to file. " + e.getMessage());
			return false;
		}
		return true;
	}
}
