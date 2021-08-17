/**
 * This class indexes the provided document collection and stores the documents as well as the 
 * necessary auxiliary data structures on disk at the provided location.
 * 
 * @author ryanhow
 *
 */

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectOutputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

public class IndexEngine {
	
	private static Logger LOGGER = Logger.getLogger(IndexEngine.class.getName());
	public static String DOC_END_TAG = "</DOC>";
	public static String DOCNO_TAG_NAME = "DOCNO";
	public static String HEADLINE_TAG_NAME = "HEADLINE";
	public static String P_TAG_NAME = "P";
	public static String TEXT_TAG_NAME = "TEXT";
	public static String GRAPHIC_TAG_NAME = "GRAPHIC";
	public static String METADATA_FILE_NAME = "metadata.txt";
	public static String LEXICON_FILE_NAME = "lexicon.txt";
	public static String INVERTED_INDEX_FILE_NAME = "invertedIndex.txt";
	
	public static void main(String[] args) throws Exception {
		
		if(args.length != 2) {
			LOGGER.log(Level.SEVERE, "Invalid input. Usage: 'java " + IndexEngine.class.getName() + " <PATH_TO_LATIMES.GZ_FILE> <PATH_TO_INDEX_DIRECTORY>'");
			return;
		}
		
		//Check existence of file path
		Path pathToLaTimesFile = Paths.get(args[0]);
		if(!Files.exists(pathToLaTimesFile)) {
			LOGGER.log(Level.SEVERE, "File '" + args[0] + "' does not exist.");
			return;
		}
		
		//Check existence of index directory
		Path pathToIndex = Paths.get(args[1]);
		if(Files.exists(pathToIndex)) {
			LOGGER.log(Level.SEVERE, "Directory '" + args[1] + "' already exists. Please provide a directory which does not already exist.");
			return;
		}
		
		//Create directory
		try { 
			Files.createDirectories(pathToIndex);
		} catch(Exception e) {
			LOGGER.log(Level.SEVERE, "Failed to create directory '" + args[1] + "'. " + e.getMessage());
			return;
		}
		
		//Formatting 
		StringBuffer formattedPathToIndex = new StringBuffer();
		formattedPathToIndex.append(args[1]);
		if(formattedPathToIndex.charAt(formattedPathToIndex.length() - 1) != '/') {
			formattedPathToIndex.append('/');
		}
		
		//Read provided gzipped file
		FileInputStream fis;
		GZIPInputStream gis;
		try {
			fis = new FileInputStream(args[0]);
			gis = new GZIPInputStream(fis);
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, e.getMessage());
			return;
		}
		
		InputStreamReader isr = new InputStreamReader(gis);
		BufferedReader br = new BufferedReader(isr);
		
		StringBuffer docSb = new StringBuffer(); //Holds one entire document at a time
		String nextLine; //Holds content for the next line
		int internalId = 0;
		
		Map<Integer, List<String>> metadataMap = new HashMap<>(); //Map to hold internalId -> {docno, headline, date, length}
		Map<String, Integer> lexicon = new HashMap<>(); //Map to hold token string -> token id
		Map<Integer, List<Integer>> invertedIndex = new HashMap<>(); //Map to hold token id -> {internalId, count, internalId, count,...}
		
		while((nextLine = br.readLine()) != null) { 
			if(nextLine.contains(DOC_END_TAG)) { //Indicates end of doc
				docSb.append(nextLine); //Append the </DOC> closing tag
				
				Document xml = getXmlDocument(docSb.toString()); //Obtain XML representation of String document
				if(xml == null) {
					LOGGER.log(Level.SEVERE, "Error obtaining XML representation of document.");
					return;
				}
				
				int documentLength = 0;
				String text = getTextFromXml(xml);
				if(text.length() != 0) { //It is possible that there is no content within TEXT, HEADLINE or GRAPHIC tags
					List<String> tokens = tokenize(text);
					documentLength = tokens.size(); 
					tokens = stem(tokens);
					List<Integer> tokenIds = convertTokensToIds(tokens, lexicon);
					Map<Integer, Integer> wordCounts = countWords(tokenIds);
					addToPostings(wordCounts, internalId, invertedIndex);
				}
				
				String docNo = getDocNoFromXml(xml);
				String headline = getHeadlineFromXml(xml);
				String date = getDateFromDocNo(docNo);
				
				//Store document in provided directory
				boolean docStoreSuccess = storeDocument(docSb.toString(), docNo, formattedPathToIndex.toString(), date);
				if(!docStoreSuccess) {
					LOGGER.log(Level.SEVERE, "Error storing document on disk.");
					return;
				}
				
				//Store metadata
				List<String> metadata = new ArrayList<>();
				metadata.add(docNo);
				metadata.add(headline);
				metadata.add(date);
				metadata.add(Integer.toString(documentLength)); 
				
				metadataMap.put(internalId, metadata);
				
				docSb.delete(0, docSb.length()); //"Reset" the docSb to be empty
				internalId++;
			} else {
				docSb.append(nextLine);
				docSb.append("\n");
			}	
		}
		
		// Write serialized data structures to disk
		StringBuffer metadataFileName = new StringBuffer();
		metadataFileName.append(formattedPathToIndex.toString());
		metadataFileName.append(METADATA_FILE_NAME);

		boolean storeMetadataSuccess = storeMap(metadataMap, metadataFileName.toString());
		if (!storeMetadataSuccess) {
			LOGGER.log(Level.SEVERE, "Error writing metadata map to disk.");
			return;
		}

		StringBuffer lexiconFileName = new StringBuffer();
		lexiconFileName.append(formattedPathToIndex.toString());
		lexiconFileName.append(LEXICON_FILE_NAME);

		boolean storeLexiconSuccess = storeMap(lexicon, lexiconFileName.toString());
		if (!storeLexiconSuccess) {
			LOGGER.log(Level.SEVERE, "Error writing lexicon map to disk.");
			return;
		}

		StringBuffer invertedIndexFileName = new StringBuffer();
		invertedIndexFileName.append(formattedPathToIndex.toString());
		invertedIndexFileName.append(INVERTED_INDEX_FILE_NAME);

		boolean invertedIndexStoreSuccess = storeMap(invertedIndex, invertedIndexFileName.toString());
		if (!invertedIndexStoreSuccess) {
			LOGGER.log(Level.SEVERE, "Error writing inverted index map to disk.");
			return;
		}

		return;
	}
	
	/**
	 * This method attempts to parse the provided string representation of the document to
	 * an XML representation.
	 * 
	 * @param	document  the string representation of the document
	 * @return			  the document parsed to XML or null on error
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
	 * This method extracts the text content of a document from the TEXT, HEADLINE and 
	 * GRAPHIC tags. All tags within these tags are also removed. 
	 * 
	 * @param	xml  the XML representation of the document	
	 * @return		 the text of a document or an empty string on error 
	 */
	private static String getTextFromXml(Document xml) {
		if (xml == null) {
			LOGGER.log(Level.SEVERE, "XML document provided to getTextFromXml was null.");
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
	 * This method applies a tokenization scheme (see below) on the provided text.
	 * 1. Downcase text
	 * 2. Split on non-alphanumerics
	 * 
	 * @param	text  the text content of the document
	 * @return		  the list of string tokens or null on error	    
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
	 * @param tokens  the list of tokens to be stemmed
	 * @return		  the list of stemmed tokens or null on error
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
	 * This method converts the token strings to integer ids with the help of the lexicon.
	 * 
	 * @param tokens	the list of token strings to be converted
	 * @param lexicon	the mapping from token string to token id
	 * @return			the list of token ids or null on error
	 */
	private static List<Integer> convertTokensToIds(List<String> tokens, Map<String, Integer> lexicon) {
		if(tokens == null || tokens.size() == 0 || lexicon == null) {
			LOGGER.log(Level.SEVERE, "Invalid input provided to convertTokensToIds.");
			return null;
		}
		List<Integer> tokenIds = new ArrayList<>();
		for (String token : tokens) {
			if (lexicon.containsKey(token)) {
				tokenIds.add(lexicon.get(token));
			} else {
				int id = lexicon.size();
				lexicon.put(token, id);
				tokenIds.add(id);
			}
		}
		return tokenIds;
	}

	/**
	 * This method counts the occurrences of each token id.
	 * 
	 * @param tokenIds	the list of token ids
	 * @return			the map from the token id to the count of it or null on error
	 */
	private static Map<Integer, Integer> countWords(List<Integer> tokenIds) {
		if(tokenIds == null || tokenIds.size() == 0) {
			LOGGER.log(Level.SEVERE, "tokenIds provided to countWords was null or empty.");
			return null;
		}
		Map<Integer, Integer> wordCounts = new HashMap<>();
		for (int tokenId : tokenIds) {
			if (wordCounts.containsKey(tokenId)) {
				wordCounts.put(tokenId, wordCounts.get(tokenId) + 1);
			} else {
				wordCounts.put(tokenId, 1);
			}
		}
		return wordCounts;
	}
	
	/**
	 * This method adds entries to the postings list for the document as well as the inverted index.
	 * 
	 * @param wordCounts	the map from the token id to the count of it
	 * @param internalId	the internal id of the document
	 * @param invertedIndex	the map from internal id to the postings list 
	 */
	private static void addToPostings(Map<Integer, Integer> wordCounts, int internalId, Map<Integer, List<Integer>> invertedIndex) {
		if(wordCounts == null || wordCounts.size() == 0 || internalId < 0 || invertedIndex == null) {
			LOGGER.log(Level.SEVERE, "Invalid input provided to addToPostings.");
			return;
		}
		for(int termid : wordCounts.keySet()) {
			int wordCount = wordCounts.get(termid);
			List<Integer> postings;
			if(invertedIndex.containsKey(termid)) {
				postings = invertedIndex.get(termid);
			} else {
				postings = new ArrayList<>();
			}
			postings.add(internalId);
			postings.add(wordCount);
			invertedIndex.put(termid, postings);
		}
	}
	
	/**
	 * This method obtains the docno value from the XML representation of the document.
	 * 
	 * @param xml  the XML representation of the document
	 * @return	   the docno of the document or an empty string on error
	 */
	private static String getDocNoFromXml(Document xml) {
		if (xml == null) {
			LOGGER.log(Level.SEVERE, "xml provided to getDocNoFromXml was null.");
			return "";
		}
		NodeList nodeList = xml.getElementsByTagName(DOCNO_TAG_NAME);
		if (nodeList.getLength() == 0) {
			return "";
		}
		Element e = (Element) nodeList.item(0);
		return e.getTextContent().trim();
	}

	/**
	 * This method obtains the headline value from the XML representation of the document.
	 * 
	 * @param xml  the XML representation of the document
	 * @return	   the headline of the document or an empty string on error
	 */
	private static String getHeadlineFromXml(Document xml) {
		if (xml == null) {
			LOGGER.log(Level.SEVERE, "XML document provided to getHeadlineFromXml was null.");
			return "";
		}
		NodeList nodeList = xml.getElementsByTagName(HEADLINE_TAG_NAME);
		if (nodeList.getLength() == 0) {
			return "";
		}
		Element e = (Element) nodeList.item(0);
		StringBuilder sb = new StringBuilder();
		int pTags = e.getElementsByTagName(P_TAG_NAME).getLength();
		for (int i = 0; i < pTags; i++) {
			sb.append(e.getElementsByTagName(P_TAG_NAME).item(i).getTextContent());
		}
		return sb.toString();
	}
	
	/**
	 * This method obtains the date embedded in the docno of the document. 
	 * 
	 * @param docNo	the docno of the document
	 * @return		the embedded date or an empty string on error
	 */
	private static String getDateFromDocNo(String docNo) {
		if (docNo == null || docNo.length() == 0) {
			LOGGER.log(Level.SEVERE, "docNo provided to getDateFromDocNo was null or empty.");
			return "";
		}
		return docNo.substring(2, 8);
	}
	
	/**
	 * This method attempts to store the document on disk at the directory mm/dd/yy.
	 * 
	 * @param document		the string representation of the document
	 * @param docNo			the docno of the document
	 * @param directoryPath	the directory of the index
	 * @param date			the date obtained from the docno
	 * @return				true on success, false on error
	 */
	private static boolean storeDocument(String document, String docNo, String directoryPath, String date) {
		if(document == null || document.length() == 0 || docNo == null || docNo.length() == 0 || directoryPath == null || directoryPath.length() == 0 || date == null || date.length() == 0) {
			LOGGER.log(Level.SEVERE, "Invalid input provided to storeDocument.");
			return false;
		}
		
		StringBuffer fileDirectory = new StringBuffer(); // Directory where file will be stored
		fileDirectory.append(directoryPath);
		fileDirectory.append(date.substring(0,2) + "/");
		fileDirectory.append(date.substring(2,4) + "/");
		fileDirectory.append(date.substring(4,6) + "/");

		File fileD = new File(fileDirectory.toString());

		if (!fileD.exists()) { // Check if directory exists and create if needed
			boolean createFileDirectory = fileD.mkdirs();
			if (!createFileDirectory) {
				LOGGER.log(Level.SEVERE, "Error creating directory '" + fileDirectory.toString() + "'.");
				return false;
			}
		}

		fileDirectory.append(docNo + ".txt");
		File file = new File(fileDirectory.toString());
		FileWriter fw;

		try {
			file.createNewFile();
			fw = new FileWriter(file);
			fw.write(document); 
			fw.close();
		} catch (IOException e) {
			LOGGER.log(Level.SEVERE, "Error writing file to disk. " + e.getMessage());
			return false;
		}
		return true;
	}
	
	/**
	 * This method attempts to store the provided map on disk 
	 * 
	 * @param content	the map to store 
	 * @param fileName	the name of the file to write to disk
	 * @return			true on success, false on error
	 */
	private static boolean storeMap(Map content, String fileName) {
		try {
			FileOutputStream fos = new FileOutputStream(fileName);
			ObjectOutputStream oos = new ObjectOutputStream(fos);
			oos.writeObject(content);
			oos.close();
			fos.close();
		} catch (IOException e) {
			LOGGER.log(Level.SEVERE, "Error writing map to disk. " + e.getMessage());
			return false;
		}
		return true;
	}
	
	
	
}
