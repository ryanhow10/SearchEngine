
/**
 * This class acts as an interactive command line search engine. It prompts the user to enter
 * a query, performs BM25 retrieval and then displays the search engine response
 * page (SERP). The user can then view a specific document, issue a new query or
 * quit the progam.
 * 
 * @author ryanhow
 *
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.StringReader;
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

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

public class SearchEngine {
	
	private static final Logger LOGGER = Logger.getLogger(SearchEngine.class.getName());
	
	public static String METADATA_FILE_NAME = "metadata.txt";
	public static String LEXICON_FILE_NAME = "lexicon.txt";
	public static String INVERTED_INDEX_FILE_NAME = "invertedIndex.txt";
	
	public static int METADATA_DOCNO_INDEX = 0;
	public static int METADATA_HEADLINE_INDEX = 1;
	public static int METADATA_DATE_INDEX = 2;
	public static int METADATA_DOC_LENGTH_INDEX = 3;
	
	public static String TEXT_TAG_NAME = "TEXT";
	public static String GRAPHIC_TAG_NAME = "GRAPHIC";
	public static String HEADLINE_TAG_NAME = "HEADLINE";
	
	public static double k1 = 1.2;
	public static double b = 0.75;
	public static double k2 = 7;
	
	public static void main(String[] args) throws ClassNotFoundException, IOException {
		
		if(args.length != 1) { 
			LOGGER.log(Level.SEVERE, "Invalid input. Usage 'java " + SearchEngine.class.getName() + " <PATH_TO_INDEX_DIRECTORY>'");
			return;
		}
		
		//Check existence of directory
		Path pathToIndexDirectory = Paths.get(args[0]);
		if (!Files.exists(pathToIndexDirectory)) {
			LOGGER.log(Level.SEVERE, "Directory '" + args[0] + "' does not exist.");
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
		int numDocs = metadataMap.size(); 
				
		StringBuffer lexiconFilePath = new StringBuffer();
		lexiconFilePath.append(formattedPathToIndexDirectory.toString());
		lexiconFilePath.append(LEXICON_FILE_NAME);
						
		Map<String, Integer> lexicon = getMap(lexiconFilePath.toString()); //Map to go from token string -> token id
	
		StringBuffer invertedIndexFilePath = new StringBuffer();
		invertedIndexFilePath.append(formattedPathToIndexDirectory.toString());
		invertedIndexFilePath.append(INVERTED_INDEX_FILE_NAME);
						
		Map<Integer, List<Integer>> invertedIndex = getMap(invertedIndexFilePath.toString()); //Map to go from token id -> {internalId, count, internalId, count,...}
	
		Scanner scanner = new Scanner(System.in);
		while(true) {
			//Prompt user for query
			String rawQuery = getRawQueryFromUser(scanner);
			System.out.println();
			
			rawQuery = rawQuery.trim();
			if(rawQuery.length() == 0) { //Query was just a space character 
				System.out.println("No results found");
				System.out.println();
				continue;
			}
			
			//Begin timer
			long startTime = System.currentTimeMillis();
			
			//Perform BM25 retrieval
			List<String> tokens = tokenize(rawQuery);
			tokens = stem(tokens);
			
			if(tokens.size() == 0) { //No tokens in query
				System.out.println("No results found");
				System.out.println();
				continue;
			}
		
			Map<Integer, Integer> tokenCounts = countTokens(tokens, lexicon);
			
			if(tokenCounts.size() == 0) { //Tokens exist but none exist in our lexicon
				System.out.println("No results found");
				System.out.println();
				continue;
			}
			
			List<Integer> tokenIds = getTokenIdsFromTokens(tokens, lexicon); 
			
			Map<Integer, Double> accumulatorMap = performBM25(tokenIds, metadataMap, invertedIndex, tokenCounts, averageDocLength, numDocs);
			
			AccumulatorEntry[] sortedAccumulatorEntries = getSortedAccumulatorEntries(accumulatorMap);
			
			Map<Integer, String> rankToDocumentMap = new HashMap<>(); //Allows for fast retrieval when user selects a rank to view
			
			//Display top 10 ranked results
			for(int i = 0; i < 10; i++) {
				if(i == sortedAccumulatorEntries.length) { //Less than 10 results
					break;
				}
				int docId = sortedAccumulatorEntries[i].getDocId();
				int rank = i + 1;
				
				String rawDate = metadataMap.get(docId).get(METADATA_DATE_INDEX); //This will be in the format of MMDDYY
				String formattedDate = getFormattedDate(rawDate);
				String docNo = metadataMap.get(docId).get(METADATA_DOCNO_INDEX);
				
				String pathToDocument = getPathToDocument(formattedPathToIndexDirectory.toString(), formattedDate, docNo);
				String document = getDocument(pathToDocument);
				rankToDocumentMap.put(rank, document);
				Document xml = getXmlDocument(document);
				String textContent = getTextFromXml(xml); 
				
				String queryBiasedSnippet = getQueryBiasedSnippet(textContent, tokens);
				
				String possibleHeadline = metadataMap.get(docId).get(METADATA_HEADLINE_INDEX);
				String headline = possibleHeadline.length() > 0 ? 
						possibleHeadline : 
						queryBiasedSnippet.length() <= 50 ? 
								queryBiasedSnippet : 
								queryBiasedSnippet.substring(0, 50) + "..."; 
				
				System.out.println(rank + ". " + headline.trim().replace("\r\n", " ").replace("\n", " ") + " (" + formattedDate + ")");
				System.out.println(queryBiasedSnippet.trim().replace("\r\n", " ").replace("\n", " ") + "(" + docNo + ")");
				System.out.println();
			}
			
			//End timer
			long endTime = System.currentTimeMillis();
			
			System.out.println("Retrieval took " + (endTime - startTime)/1000F + " seconds."); 
			System.out.println();
			
			while(true) {
				System.out.print("Enter 1-10 to view a ranked document, n/N to execute new query or q/Q to quit: ");
				String nextAction = scanner.nextLine().trim();
				
				if(nextAction.equals("n") || nextAction.equals("N")) { //New query
					break;
				} else if(nextAction.equals("q") || nextAction.equals("Q")) { //Quit
					return;
				} else { //Fetch doc
					try {
						int rank = Integer.parseInt(nextAction);
						if(rank < 1 || rank > 10) {
							System.out.println("Rank must be between 1-10");
							continue;
						}
						String document = rankToDocumentMap.get(rank);
						System.out.println(document);
						System.out.println();
					} catch(Exception e) {
						System.out.println("Invalid input");
					}
				}
			}
			System.out.println();
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
		if(filePath == null || filePath.length() == 0) {
			LOGGER.log(Level.SEVERE, "filePath provided to getMap was null or empty.");
			return null;
		}
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
			LOGGER.log(Level.SEVERE, "Error getting map from disk for file '" + filePath + "'. " + e.getMessage());
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
			LOGGER.log(Level.SEVERE, "metadataMap provided to getAverageDocLength was null or empty.");
			return -1.0;
		}
		double totalDocLengths = 0;
		for(List<String> metadata : metadataMap.values()) {
			totalDocLengths += Double.parseDouble(metadata.get(METADATA_DOC_LENGTH_INDEX));
		}
		return totalDocLengths / metadataMap.size();
	}
	

	/**
	 * This method obtains the user's query from standard input.
	 * 
	 * @param scanner the scanner object
	 * @return the raw query inputted by the user
	 */
	private static String getRawQueryFromUser(Scanner scanner) {
		System.out.print("Please enter a query: ");
		String rawQuery = scanner.nextLine();
		return rawQuery;
	}
	
	/**
	 * This method applies a tokenization scheme (see below) on the provided text.
	 * 1. Downcase text 
	 * 2. Split on non-alphanumerics
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
	 * @return the BM25 score or -1.0 on error
	 */
	private static double calculateBM25(int frequencyInDoc, int docLength, int frequencyInQuery, int numberOfDocsContainingToken, double averageDocLength, int numDocs) {
		if(frequencyInDoc < 0 || docLength < 0 || frequencyInQuery < 0 || numberOfDocsContainingToken < 0 || averageDocLength < 0 || numDocs < 0) {
			LOGGER.log(Level.SEVERE, "Invalid input provided to calculateBM25.");
			return -1.0;
		}
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
			LOGGER.log(Level.SEVERE, "accumulatorMap provided to getSortedAccumulatorEntries was null or empty.");
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
	 * This method obtains the formatted date given a date in the format of MMDDYY.
	 * 
	 * @param rawDate the date as MMDDYY
	 * @return the formatted date as MM/DD/YY or null on error
	 */
	private static String getFormattedDate(String rawDate) {
		if(rawDate == null || rawDate.length() == 0) {
			LOGGER.log(Level.SEVERE, "rawDate provided to getFormattedDate was null or empty.");
			return null;
		}
		
		StringBuffer formattedDate = new StringBuffer();
		formattedDate.append(rawDate.substring(0, 2));
		formattedDate.append("/");
		formattedDate.append(rawDate.substring(2, 4));
		formattedDate.append("/");
		formattedDate.append(rawDate.substring(4, 6));
		
		return formattedDate.toString();
	}
	
	/**
	 * This method obtains the path to the document.
	 * 
	 * @param formattedPathToIndexDirectory the path to the index directory
	 * @param formattedDate                 the formatted date as MM/DD/YY
	 * @param docNo                         the docno of the document
	 * @return the path to the document or null on error
	 */
	private static String getPathToDocument(String formattedPathToIndexDirectory, String formattedDate, String docNo) {
		if(formattedPathToIndexDirectory == null || formattedPathToIndexDirectory.length() == 0 || formattedDate == null || formattedDate.length() == 0 || docNo == null || docNo.length() == 0) {
			LOGGER.log(Level.SEVERE, "Invalid input provided to getPathToDocument.");
			return null;
		}
		StringBuilder pathToDocument = new StringBuilder();
		pathToDocument.append(formattedPathToIndexDirectory);
		pathToDocument.append(formattedDate);
		pathToDocument.append("/" + docNo + ".txt");
		
		return pathToDocument.toString();
	}
	
	/**
	 * This method obtains the document from disk.
	 * 
	 * @param pathToDocument the path to the document to retrieve
	 * @return the document
	 * @throws FileNotFoundException
	 */
	private static String getDocument(String pathToDocument) throws FileNotFoundException {
		StringBuffer document = new StringBuffer();
		File f = new File(pathToDocument);
		Scanner scanner = new Scanner(f);
		while (scanner.hasNextLine()) {
			document.append(scanner.nextLine());
			document.append("\n");
		}
		return document.toString();
	}
	
	/**
	 * This method attempts to parse the provided string representation of the
	 * document to an XML representation.
	 * 
	 * @param document the string representation of the document
	 * @return the document parsed to XML or null on error
	 */
	private static Document getXmlDocument(String document) {
		if (document == null || document.length() == 0) {
			LOGGER.log(Level.SEVERE, "document provided to getXmlDocument was null or empty.");
			return null;
		}
		Document xmlDoc;
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			DocumentBuilder builder = factory.newDocumentBuilder();
			InputSource is = new InputSource(new StringReader(document));
			xmlDoc = builder.parse(is);
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, "Error parsing document. " + e.getMessage());
			return null;
		}
		return xmlDoc;
	}
	
	/**
	 * This method extracts the text content of a document from the TEXT, HEADLINE
	 * and GRAPHIC tags. All tags within these tags are also removed.
	 * 
	 * @param xml the XML representation of the document
	 * @return the text of a document or an empty string on error
	 */
	private static String getTextFromXml(Document xml) {
		if (xml == null) {
			LOGGER.log(Level.SEVERE, "xml provided to getTextFromXml was null.");
			return "";
		}
		StringBuffer textContent = new StringBuffer();

		// Get TEXT tag content
		NodeList nodeList = xml.getElementsByTagName(TEXT_TAG_NAME);
		Element e;
		if (nodeList.getLength() != 0) {
			e = (Element) nodeList.item(0);
			textContent.append(e.getTextContent());
		}

		// Get HEADLINE content
		nodeList = xml.getElementsByTagName(HEADLINE_TAG_NAME);
		if (nodeList.getLength() != 0) {
			e = (Element) nodeList.item(0);
			textContent.append(e.getTextContent());
		}

		// Get GRAPHIC content
		nodeList = xml.getElementsByTagName(GRAPHIC_TAG_NAME);
		if (nodeList.getLength() != 0) {
			e = (Element) nodeList.item(0);
			textContent.append(e.getTextContent());
		}

		// Remove all tags within textContent
		textContent.toString().replaceAll("\\<.*?\\>", "");

		return textContent.toString();
	}

	/**
	 * This method obtains the query-biased snippet for the document.
	 * 
	 * @param textContent the text content of the document
	 * @param queryTokens the query tokens for the query
	 * @return the query-biased snippet or null on error
	 */
	private static String getQueryBiasedSnippet(String textContent, List<String> queryTokens) {
		if(textContent == null || textContent.length() == 0 || queryTokens == null || queryTokens.size() == 0) {
			LOGGER.log(Level.SEVERE, "Invalid input provided to getQueryBiasedSnippet.");
			return null;
		}
		
		List<QuerySnippetSentence> querySnippetSentenceList = new ArrayList<>(); 
		
		//Extract sentences within textContent
		int start = 0;
		int end = 0;
		while(end < textContent.length()) {
			char endChar = textContent.charAt(end);
			if(endChar == '.' || endChar == '!' || endChar == '?') {
				String sentence = textContent.substring(start, end + 1).trim();
				if(sentence.split(" ").length < 5) { //Discard sentences which contain less than 5 words
					start = end + 1;
					end++;
					continue;
				}
				List<String> tokenizedSentence = tokenize(sentence); 
				tokenizedSentence = stem(tokenizedSentence);
				querySnippetSentenceList.add(new QuerySnippetSentence(sentence, tokenizedSentence, 0)); //Score of 0 for now... will be updated later on
				start = end + 1;
			}
			end++;
		}
		
		Set<String> queryTokenSet = new HashSet<>(queryTokens);
		
		//Calculate scores for each sentence
		for(int i = 0; i < querySnippetSentenceList.size(); i++) {
			QuerySnippetSentence querySnippetSentence = querySnippetSentenceList.get(i);
			
			int l = 0;
			if(i == 0) { //First sentence 
				l = 2;
			}
			if(i == 1) { //Second sentence
				l = 1;
			}
			
			Map<String, Integer> queryTokenCounts = getQueryTokenCounts(querySnippetSentence.getTokenized(), queryTokenSet);
			
			int c = queryTokenCounts.size() > 0 ? getC(queryTokenCounts) : 0; //Possible that sentence has 0 query terms in it and therefore queryTokenCounts map is empty
			int d = queryTokenCounts.size();
			int k = getK(querySnippetSentence.getTokenized(), queryTokenSet);
			
			querySnippetSentence.setScore(l + c + d + k);
		}
		
		QuerySnippetSentence[] querySnippetSentenceArray = new QuerySnippetSentence[querySnippetSentenceList.size()];
		for(int i = 0; i < querySnippetSentenceArray.length; i++) {
			querySnippetSentenceArray[i] = querySnippetSentenceList.get(i);
		}
		Arrays.sort(querySnippetSentenceArray); //Sort by score in descending order
		
		StringBuilder queryBiasedSnippet = new StringBuilder(); //First 2 sentences
		for(int i = 0; i < 2; i++) {
			if(i == querySnippetSentenceArray.length) {
				break;
			}
			queryBiasedSnippet.append(querySnippetSentenceArray[i].getOriginal()); 
			queryBiasedSnippet.append(" ");
		}
		
		return queryBiasedSnippet.toString();
	}
	
	/**
	 * This method obtains the count of each query token in the sentence.
	 * 
	 * @param sentenceTokens the tokens in the sentence
	 * @param queryTokenSet  the set of query tokens
	 * @return the map from query token to the count of it in the sentence or null on error
	 */
	private static Map<String, Integer> getQueryTokenCounts(List<String> sentenceTokens, Set<String> queryTokenSet) {
		if (sentenceTokens == null || sentenceTokens.size() == 0 || queryTokenSet == null || queryTokenSet.size() == 0) {
			LOGGER.log(Level.SEVERE, "Invalid input provided to getQueryTokenCounts.");
			return null;
		}

		Map<String, Integer> queryTokenCounts = new HashMap<>();
		for (String sentenceToken : sentenceTokens) {
			if (queryTokenSet.contains(sentenceToken)) {
				if (queryTokenCounts.containsKey(sentenceToken)) {
					queryTokenCounts.put(sentenceToken, queryTokenCounts.get(sentenceToken) + 1);
				} else {
					queryTokenCounts.put(sentenceToken, 1);
				}
			}
		}
		return queryTokenCounts;

	}
		
	/**
	 * This method obtains the count of the number of sentence tokens that are query
	 * tokens.
	 * 
	 * @param queryTokenCounts the map from query token to the count of it in the sentence
	 * @return the number of sentence tokens that are query tokens or -1 on error
	 */
	private static int getC(Map<String, Integer> queryTokenCounts) {
		if (queryTokenCounts == null || queryTokenCounts.size() == 0) {
			LOGGER.log(Level.SEVERE, "queryTokenCounts provided to getC was null or empty.");
			return -1;
		}
		int c = 0;
		for (String token : queryTokenCounts.keySet()) {
			c += queryTokenCounts.get(token);
		}
		return c;
	}

	/**
	 * This method obtains the longest contiguous run of query terms (in any order)
	 * in the sentence.
	 * 
	 * @param sentenceTokens the tokens in the sentence
	 * @param queryTokenSet  the set of query tokens
	 * @return the longest contiguous run of query terms in the sentence or -1 on error
	 */
	private static int getK(List<String> sentenceTokens, Set<String> queryTokenSet) {
		if (sentenceTokens == null || sentenceTokens.size() == 0 || queryTokenSet == null || queryTokenSet.size() == 0) {
			LOGGER.log(Level.SEVERE, "Invalid input provided to getK.");
			return -1;
		}

		int k = 0;
		int dp[] = new int[sentenceTokens.size()];
		if (queryTokenSet.contains(sentenceTokens.get(0))) {
			dp[0] = 1;
			k = 1;
		} else {
			dp[0] = 0;
		}

		for (int i = 1; i < sentenceTokens.size(); i++) {
			String sentenceToken = sentenceTokens.get(i);
			if (queryTokenSet.contains(sentenceToken)) {
				dp[i] = Math.max(dp[i - 1] + 1, 1);
				k = Math.max(k, dp[i]);
			} else {
				dp[i] = 0;
			}
		}
		return k;
	}
}
