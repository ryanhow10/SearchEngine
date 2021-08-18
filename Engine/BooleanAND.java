/**
 * This class performs booleandAND retrieval given a set of query topics and queries. 
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BooleanAND {
	
	private static Logger LOGGER = Logger.getLogger(BooleanAND.class.getName()); 
	public static String METADATA_FILE_NAME = "metadata.txt";
	public static String LEXICON_FILE_NAME = "lexicon.txt";
	public static String INVERTED_INDEX_FILE_NAME = "invertedIndex.txt";
	public static int DOCNO_INDEX = 0;
	
	public static void main(String args[]) throws FileNotFoundException{
		
		if(args.length != 3) { 
			LOGGER.log(Level.SEVERE, "Invalid input. Usage: 'java " + BooleanAND.class.getName() + " <PATH_TO_INDEX_DIRECTORY> <PATH_TO_QUERIES_FILE> <OUTPUT_FILE_NAME>'");
			return;
		}
		
		//Check existence of index directory 
		Path pathToIndexDirectory = Paths.get(args[0]);
		if(!Files.exists(pathToIndexDirectory)) { 
			LOGGER.log(Level.SEVERE, "Directory '" + args[0] + "' does not exist.");
			return;
		}
		
		//Check existence of query file
		Path pathToQueriesFile = Paths.get(args[1]);
		if(!Files.exists(pathToQueriesFile)) { 
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
		if(formattedPathToIndexDirectory.charAt(formattedPathToIndexDirectory.length() - 1)  != '/') {
			formattedPathToIndexDirectory.append("/");
		}
		
		//Retrieve auxiliary maps
		StringBuffer metadataMapFilePath = new StringBuffer();
		metadataMapFilePath.append(formattedPathToIndexDirectory.toString());
		metadataMapFilePath.append(METADATA_FILE_NAME);
		
		Map<Integer, List<String>> metadataMap = getMap(metadataMapFilePath.toString()); //Map to go from internalId->{docNo, headline, date, length}
		if(metadataMap == null) {
			LOGGER.log(Level.SEVERE, "Error obtaining metadata map from disk.");
			return;
		}
		
		StringBuffer lexiconFilePath = new StringBuffer();
		lexiconFilePath.append(formattedPathToIndexDirectory.toString());
		lexiconFilePath.append(LEXICON_FILE_NAME);
		
		Map<String, Integer> lexicon = getMap(lexiconFilePath.toString()); //Map to go from token string -> token id
		if(lexicon == null) {
			LOGGER.log(Level.SEVERE, "Error obtaining lexicon map from disk.");
			return;
		}
		
		StringBuffer invertedIndexFilePath = new StringBuffer();
		invertedIndexFilePath.append(formattedPathToIndexDirectory.toString());
		invertedIndexFilePath.append(INVERTED_INDEX_FILE_NAME);
		
		Map<Integer, List<Integer>> invertedIndex = getMap(invertedIndexFilePath.toString()); //Map to go from token id -> {internalId, count, internalId, count,...}
		if(invertedIndex == null) {
			LOGGER.log(Level.SEVERE, "Error obtaining inverted index map frorm disk.");
		}
		
		//Read the queries file and construct results
		File file = new File(args[1]);
		Scanner scanner = new Scanner(file);
		
		StringBuffer results = new StringBuffer(); //Hold all query results 
		while(scanner.hasNextLine()) { 
			String rawTopicId = scanner.nextLine().trim(); //First line will be the topic id
			int topicId = Integer.parseInt(rawTopicId); 
			
			String rawQuery = scanner.nextLine().trim(); //Second line will be the actual query
			List<String> tokens = tokenize(rawQuery);
			tokens = stem(tokens);
			
			List<Integer> tokenIds = getTokenIdsFromTokens(tokens, lexicon); //Obtain unique tokenIds from query 
			
			List<Integer> internalIds = getInternalIds(tokenIds, invertedIndex); //internalIds for docs that fulfill booleanAND condition
			
			boolean addToResultsSuccess = addToResults(results, topicId, internalIds, metadataMap);
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
	 * This method attempts to deserialize and fetch the auxiliary maps stored on disk.
	 * 
	 * @param filePath	the path of the file holding the map
	 * @return			the map or null on error
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
	 * This method applies a tokenization scheme (see below) on the provided text.
	 * 1. Downcase text
	 * 2. Split on non-alphanumerics
	 * 
	 * @param	text	the text content of the document
	 * @return			the list of string tokens or null on error	    
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
	 * @param tokens	the list of tokens to be stemmed
	 * @return			the list of stemmed tokens or null on error
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
	 * This method makes use of the lexicon to obtain the unique token ids from the token strings.
	 * 
	 * @param tokens	the list of token strings
	 * @param lexicon	the map from the token string to token id
	 * @return			the list of token ids or null on error
	 */
	private static List<Integer> getTokenIdsFromTokens(List<String> tokens, Map<String, Integer> lexicon) {
		if(tokens == null || tokens.size() == 0 || lexicon == null || lexicon.size() == 0) {
			LOGGER.log(Level.SEVERE, "Invalid input provided to getTokenIdsFromTokens.");
			return null;
		}
		List<Integer> tokenIds = new ArrayList<>();
		for(String token : tokens) {
			if(lexicon.containsKey(token)) { //Only consider tokens in the lexicon
				int tokenId = lexicon.get(token);
				if(!tokenIds.contains(tokenId)) { 
					tokenIds.add(tokenId);
				}
			}
		}
		return tokenIds;
	}
	
	/**
	 * This method obtains the internal ids of documents fulfilling the booleanAND condition.
	 * 
	 * @param tokenIds		the list of token ids
	 * @param invertedIndex	the map from token id to postings list (internalId, docno, internalId, docno,...)
	 * @return				the list of internal ids
	 */
	private static List<Integer> getInternalIds(List<Integer> tokenIds, Map<Integer, List<Integer>> invertedIndex) {
		List<Integer> internalIds = new ArrayList<>(); 
		if(tokenIds.size() == 0) { //Return empty list if no tokenIds
			return internalIds;
		} else if(tokenIds.size() == 1) { 
			internalIds = onlyInternalIds(invertedIndex.get(tokenIds.get(0)));
		} else {
			List<Integer> intersectList = intersect(invertedIndex.get(tokenIds.get(0)), invertedIndex.get(tokenIds.get(1)));
			for(int i = 2; i < tokenIds.size(); i++) {
				intersectList = intersect(intersectList, invertedIndex.get(tokenIds.get(i)));
			}
			internalIds = onlyInternalIds(intersectList); 
		}
		return internalIds;
	}
	
	/**
	 * This method obtains the intersection elements between two lists.
	 * 
	 * @param l1	the first list
	 * @param l2	the second list
	 * @return		the list of intersection elements
	 */
	private static List<Integer> intersect(List<Integer> l1, List<Integer> l2) {
		List<Integer> intersect = new ArrayList<>();
		int i = 0;
		int j = 0;
		while (i < l1.size() && j < l2.size()) {
			int l1Val = l1.get(i);
			int l2Val = l2.get(j);
			if (l1Val == l2Val) {
				intersect.add(l1Val);
				intersect.add(-1);
				i += 2;
				j += 2;
			} else if (l1Val < l2Val) {
				i += 2;
			} else {
				j += 2;
			}
		}
		return intersect;
	}
	
	/**
	 * This method obtains only the internal ids. 
	 * 
	 * @param list	the list of elements alternating between the internal id and a placeholder value (-1)
	 * @return		the list of internal ids
	 */
	private static List<Integer> onlyInternalIds(List<Integer> list) {
		List<Integer> internalIds = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			internalIds.add(list.get(i));
			i++;
		}
		return internalIds;
	}
	
	/**
	 * This method appends a result in a specified format (see below) to the results.
	 * The result is formatted in the following format
	 * topicId Q0 docno rank score rykhowteAND
	 * 
	 * @param results		the string buffer holding all results
	 * @param topicId		the topic id for the query
	 * @param internalIds	the internal ids of the documents retrieved
	 * @param metadataMap	the map from internal id to metadata (docno, headline, date, length)
	 * @return				true on success, false on error
	 */
	private static boolean addToResults(StringBuffer results, int topicId, List<Integer> internalIds, Map<Integer, List<String>> metadataMap) {
		if(results == null || topicId < 0 || internalIds == null || metadataMap == null || metadataMap.size() == 0) {
			LOGGER.log(Level.SEVERE, "Invalid input provided to addToResults.");
			return false;
		}
		for(int i = 0; i < internalIds.size(); i++) {
			results.append(topicId);
			results.append(" ");
			results.append("Q0");
			results.append(" ");
			results.append(metadataMap.get(internalIds.get(i)).get(DOCNO_INDEX));
			results.append(" ");
			results.append(i + 1);
			results.append(" ");
			results.append(internalIds.size() - i);
			results.append(" ");
			results.append("rykhowteAND");
			results.append("\n");
		}
		return true;
	}
	
	/**
	 * This method writes the results to disk at the provided file.
	 * 
	 * @param results	the results from booleanAND retrieval
	 * @param file		the file to write the results to
	 * @return			true on success, false on error
	 */
	private static boolean writeResults(String results, String file) {
		File f = new File(file);
		FileWriter fw;
		
		try {
			f.createNewFile(); 
			fw = new FileWriter(file);
			fw.write(results); 
			fw.close(); 
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, "Error writing results file to disk.");
			return false;
		}
		return true;
	}
}
