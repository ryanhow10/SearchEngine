public class AccumulatorEntry implements Comparable<AccumulatorEntry>{

	private int docId;
	private double score;
	
	public AccumulatorEntry(int docId, double score) {
		super();
		this.docId = docId;
		this.score = score;
	}

	public int getDocId() {
		return docId;
	}
	
	public void setDocId(int docId) {
		this.docId = docId;
	}
	
	public double getScore() {
		return score;
	}
	
	public void setScore(double score) {
		this.score = score;
	}

	@Override
	public int compareTo(AccumulatorEntry o) {
		return Double.compare(o.score, this.score);
	}
	
	
}
