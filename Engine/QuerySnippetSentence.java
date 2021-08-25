import java.util.List;

public class QuerySnippetSentence implements Comparable<QuerySnippetSentence>{
	
	private String original;
	private List<String> tokenized;
	private int score;
	
	public QuerySnippetSentence(String original, List<String> tokenized, int score) {
		super();
		this.original = original;
		this.tokenized = tokenized;
		this.score = score;
	}

	public String getOriginal() {
		return original;
	}
	
	public void setOriginal(String original) {
		this.original = original;
	}
	
	public List<String> getTokenized() {
		return tokenized;
	}
	
	public void setTokenized(List<String> tokenized) {
		this.tokenized = tokenized;
	}
	
	public int getScore() {
		return score;
	}
	
	public void setScore(int score) {
		this.score = score;
	}

	@Override
	public int compareTo(QuerySnippetSentence o) {
		return o.score - this.score;
	}

}
