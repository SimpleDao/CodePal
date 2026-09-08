package com.codepal.model;

public class UserAnswer {
    private String question;
    private String answer;
    private boolean answered;

    public UserAnswer() {}

    public UserAnswer(String question, String answer, boolean answered) {
        this.question = question;
        this.answer = answer;
        this.answered = answered;
    }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public boolean isAnswered() { return answered; }
    public void setAnswered(boolean answered) { this.answered = answered; }
}
