# Search Engine

**NOTE:** The search engine utilizes the 1989-1990 LATimes collection for retrieval.

---

### BM25
This program performs [BM25 retrieval](https://en.wikipedia.org/wiki/Okapi_BM25) given a set of query topics and queries. The equation used for BM25 can be found below. Documents which have higher BM25 scores appear higher on the ranked list of results. 

<p align="center">
<img width="392" alt="Screen Shot 2021-08-26 at 5 48 46 PM" src="https://user-images.githubusercontent.com/48066840/131040639-01cb98f1-dcaa-4c35-9542-f825316ed821.png">
</p>

&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;k1 = tuning parameter controlling the saturation for term frequency in the document\
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;k2 = tuning parameter controlling the saturation for term frequency in the query\
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;b = tuning parameter controlling the amount of length normalization\
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;fi = frequency of term i in the document\
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;qfi = frequency of term i in the query\
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;ni = number of documents in the collection which contain term i\
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;dl = document length\
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;avdl = average document length for the collection

The program requires a query file as its second argument. Its format is specified below.

**Query File Format**\
Topic ID (integer)\
Query (string)

For example, a valid query file would look something like this.\
*queries.txt*\
105\
Basketball players\
256\
Global warming

The program outputs a results file. Its format is specified below.

**Output File Format**\
topicID (integer)  Q0 (string)  docno (string)  rank (integer)  score (double)  runTag (string)  

topicID = topic number\
Q0 = letter Q followed by the number 0\
docno = document’s docno\
rank = rank of the document from 1 to N where N is the number of documents retrieved\
score = numeric value such that lower ranks have higher scores\
runTag = unique string identifying a run

---

### BooleanAND
This program performs BooleanAND retrieval given a set of query topics and queries. It is considered a lousy retrieval algorithm however, it is a good strawman. BooleanAND retrieval does not return a ranked list but instead returns a set of all documents in the collection which contain all query terms in them. This program requires a query file as its second argument which has the same format as specified earlier. Additionally, it outputs a results file which has the same format as specified earlier. 

---

### IndexEngine
This program indexes the document collection and creates and stores the auxiliary data structures needed for fast retrieval. The simple tokenization algorithm includes downcasing all text and splitting on non-alphanumeric characters. Then these tokens are stemmed using the [Porter Stemmer](https://tartarus.org/martin/PorterStemmer/).

There are three data structures created and stored on disk which include
- Metadata map: This map holds the mapping from the internal representation of the document (an integer id) to the list of metadata which contains the document number given by the LA Times (docno), headline, date and length of the document which is defined as the number of tokens it contains
- Lexicon: This map holds the mapping from token strings to token ids (integers)
- Inverted index: This map holds the mapping from the token ids to the counts of the tokens in each of the documents (postings list). The postings list is a list of integers which alternates between the internal id of the document and the count of the token in the document
    - For example, if two documents with internal ids 2 and 6 contained the word “apple” 3 and 2 times respectively, the postings list for “apple” would be [2,3,6,2]. It is important to note that the postings list is ordered in ascending order of document internal id

---

### SearchEngine
This program is an interactive command line search engine. It allows users to enter queries, view the top 10 ranked results (provided by BM25 retrieval), view specific documents by rank and issue new queries.

**Query & Top 10 Results**
![Screen Shot 2021-08-26 at 6 06 49 PM](https://user-images.githubusercontent.com/48066840/131042442-ee211d72-3273-4650-ac7f-1486ed3d3856.png)

**Viewing Document at Rank 3**
![Screen Shot 2021-08-26 at 6 07 18 PM](https://user-images.githubusercontent.com/48066840/131042448-d6f510f7-6dd8-4537-b22d-3f4d50104fb6.png)

---

### Evaluation
This program reports the following effectiveness measures for each topic
- [Average Precision](https://en.wikipedia.org/wiki/Evaluation_measures_(information_retrieval)#Average_precision)
- [Precision at Rank 10](https://en.wikipedia.org/wiki/Evaluation_measures_(information_retrieval)#Precision)
- [Normalized Discounted Cumulative Gain at Rank 10](https://en.wikipedia.org/wiki/Evaluation_measures_(information_retrieval)#Discounted_cumulative_gain)
- [Normalized Discounted Cumulative Gain at Rank 1000](https://en.wikipedia.org/wiki/Evaluation_measures_(information_retrieval)#Discounted_cumulative_gain)
- Time-biased Gain

The program requires a qrels file as its second argument. This file contains the relevance judgements for a set of query topics. Its format is specified below.

**Qrels File Format**\
topicID (integer)  ignore  docno (string)  judgment (integer)

topicID = topic number\
ignore = column to ignore\
docno = document’s docno\
judgment = numeric value such that values > 0 indicate relevancy

The program also requires a result file as its third argument. The format of this file is the same as the output files from the BM25 and BooleanAND programs. 


