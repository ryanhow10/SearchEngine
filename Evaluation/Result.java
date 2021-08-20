public class Result implements Comparable<Result>{
	
	private String docNo;
	private double score;
	
	public Result(String docNo, double score) {
		super();
		this.docNo = docNo;
		this.score = score;
	}

	public String getDocNo() {
		return docNo;
	}
	
	public void setDocNo(String docNo) {
		this.docNo = docNo;
	}
	
	public double getScore() {
		return score;
	}
	
	public void setScore(double score) {
		this.score = score;
	}
	
	@Override
	public String toString() {
		return "docNo = " + docNo + ", score = " + score;
	}

	//Sort in descending order of score
	@Override
	public int compareTo(Result o) {
		return Double.compare(o.score, this.score);
	}
	
}

