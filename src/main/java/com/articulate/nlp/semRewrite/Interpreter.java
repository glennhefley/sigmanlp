/*
Copyright 2014-2015 IPsoft

Author: Adam Pease adam.pease@ipsoft.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program ; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston,
MA  02111-1307 USA 
 */
package com.articulate.nlp.semRewrite;

import com.articulate.nlp.*;
import com.articulate.nlp.DependencyConverter;
import com.articulate.nlp.Document;
import com.articulate.nlp.semRewrite.datesandnumber.DateAndNumbersGeneration;
import com.articulate.nlp.semRewrite.datesandnumber.StanfordDateTimeExtractor;
import com.articulate.nlp.semRewrite.datesandnumber.Tokens;
import com.articulate.sigma.*;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.*;
import edu.stanford.nlp.ie.machinereading.structure.MachineReadingAnnotations;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreAnnotations.SentencesAnnotation;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.util.CoreMap;
import com.articulate.nlp.pipeline.Pipeline;
import com.articulate.nlp.pipeline.SentenceBuilder;
import com.articulate.nlp.pipeline.SentenceUtil;
import com.articulate.nlp.semRewrite.substitutor.*;
import edu.stanford.nlp.util.IntPair;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.articulate.nlp.semRewrite.EntityType.PERSON;

public class Interpreter {

    private static final String ANSWER_YES = "Yes.";
    private static final String ANSWER_NO = "No.";
    private static final String ANSWER_UNDEFINED = "I don't know.";

    private static final String PHRASAL_VERB_PARTICLE_TAG = "prt(";
    private static final String SUMO_TAG = "sumo(";
    private static final String TENSE_TAG = "tense(";
    private static final String NUMBER_TAG = "number(";

    private static final Pattern ENDING_IN_PUNC_PATTERN = Pattern.compile(".*[.?!]$");

    public boolean debug = false;

    public RuleSet rs = null;
    //public CNF input = null;
    public String fname = "";

    // execution options
    public boolean inference = false;
    public boolean question = false;
    public boolean ner = true;
    public static boolean addUnprocessed = false;
    //if true, show POS tags during parse
    public static boolean verboseParse = true;
    //tfidf flags
    public boolean autoir = true;
    public static boolean ir = false;
    public static boolean simFlood = false;
    //log response from prover before sending to answer generator
    public static boolean verboseAnswer = false;
    //show the proof in console
    public static boolean verboseProof = false;
    public static boolean removePolite = true;

    //timeout value
    public static int timeOut_value = 30;

    // debug options
    public static boolean showrhs = false;
    public static boolean showr = true;
    public static boolean coref = true;

    public static List<String> qwords = Lists.newArrayList("who","what","where","when","why","which","how");
    public static List<String> months = Lists.newArrayList("January","February","March","April","May","June",
            "July","August","September","October","November","December");
    public static List<String> days = Lists.newArrayList("Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday");
    public static TFIDF tfidf = null;

    //Collection of utterances by the user
    public Document userInputs = new Document();
    private ArrayList<Graph> userGraphs = new ArrayList<Graph>();

    private Pipeline p = null;
    //private String propString =  "tokenize, ssplit, pos, lemma, ner, gender, parse, depparse, dcoref, entitymentions, wnmw, wsd, tsumo";

    public static ArrayList<String> firedRules = new ArrayList<String>();
    public static ArrayList<String> augmentedClauses = new ArrayList<String>();
    public static ClauseSubstitutor substitutor = null;

    /** *************************************************************
     */
    public Interpreter () {

        p = new Pipeline(true,Pipeline.defaultProp);
    }

    /** *************************************************************
     */
    public Interpreter (RuleSet rsin) {

        canon(rsin);
        rs = rsin;
        p = new Pipeline(true,Pipeline.defaultProp);
    }

    /** *************************************************************
     */
    public static RuleSet canon(RuleSet rsin) {

        return Clausifier.clausify(rsin);
    }

    /** *************************************************************
     */
    protected Document getUserInputs() {

        return userInputs;
    }

    /** *************************************************************
     * @return a string consisting a clause argument
     */
    private static String getArg(String s, int argnum) {

        int paren = s.indexOf('(');
        int comma = s.indexOf(',');
        int lastParen = s.lastIndexOf(')');
        if (paren < 2 || comma < 4 || comma < paren) {
            System.out.println("Error in Interpreter.getArg(): bad clause format: " + s);
            return "";
        }
        String arg1 = s.substring(paren + 1,comma).trim();
        String arg2 = s.substring(comma + 1,lastParen).trim();
        if (argnum == 1)
            return arg1;
        if (argnum == 2)
            return arg2;
        System.out.println("Error in Interpreter.getArg(): bad clause number: " + argnum);
        return "";
    }

    /** *************************************************************
     * @return a string consisting of a token without a dash and its number in
     * the sentence such as walks-5 -> walks
     */
    private static String stripSuffix(String s) {

        int wordend1 = s.lastIndexOf('-');
        if (wordend1 == -1)
            return s;
        else
            return s.substring(0, wordend1);
    }

    /** *************************************************************
     * @return a map of the word key and the value as a string
     * consisting of the word plus a dash and its number in
     * the sentence such as walks-5 -> walks
     */
    private static HashMap<String,String> extractWords(List<String> clauses) {

        System.out.println("Info in Interpreter.extractWords(): clauses: " + clauses);
        HashMap<String,String> purewords = new HashMap<String,String>();
        for (int i = 0; i < clauses.size(); i++) {
            String clause = clauses.get(i);
            int paren = clause.indexOf('(');
            int comma = clause.indexOf(',');

            if (paren < 2 || comma < 4 || comma < paren) {
                System.out.println("Error in Interpreter.extractWords(): bad clause format: " + clause);
                continue;
            }
            String arg1 = clause.substring(paren + 1,comma).trim();
            int wordend1 = arg1.indexOf('-');
            if (wordend1 < 0) {
                System.out.println("Error in Interpreter.extractWords(): bad token, missing token number suffix: " + clause);
                continue;
            }
            String purearg1 = arg1.substring(0, wordend1);
            if (!purearg1.equals("ROOT"))
                purewords.put(arg1,purearg1);

            String arg2 = clause.substring(comma + 1, clause.length()-1).trim();
            int wordend2 = arg2.indexOf('-');
            if (wordend2 < 0) {
                System.out.println("Error in Interpreter.extractWords(): bad token, missing token number suffix: " + clause);
                continue;
            }
            String purearg2 = arg2.substring(0, wordend2);
            if (!purearg2.equals("ROOT"))
                purewords.put(arg2,purearg2);
        }
        System.out.println("Info in Interpreter.extractWords(): purewords: " + purewords);
        return purewords;
    }

    /** *************************************************************
     */
    public static boolean excluded(String word) {

        return (months.contains(word) || days.contains(word));
    }

    /** *************************************************************
     * replace all word tokens with SUMO instance terms.  For example
     *   prep_on(Adele-4,Spotify-6), sumoInstance(SpotifyApp,Spotify-6)
     * becomes
     *   prep_on(Adele-4,SpotifyApp)
     */
    public static List<String> replaceInstances(List<String> input) {

        //System.out.println("INFO in Interpreter.replaceInstances(): input: " + input);
        Map<String,String> replacements = new HashMap<String,String>();
        Set<String> contents = new HashSet<String>();
        List<String> results = new ArrayList<String>();
        for (String s : input) {
            if (s.startsWith("sumoInstance(")) {
                String arg1 = getArg(s,1);
                String arg2 = getArg(s,2);
                replacements.put(arg2,arg1);
            }
            else
                contents.add(s);
        }
        //System.out.println("INFO in Interpreter.replaceInstances(): replacements: " + replacements);
        //System.out.println("INFO in Interpreter.replaceInstances(): contents: " + contents);
        for (String s : contents) {
            String stringNew = s;
            for (String key : replacements.keySet())
                stringNew = stringNew.replace(key,replacements.get(key));
            results.add(stringNew);
        }
        //System.out.println("INFO in Interpreter.replaceInstances(): results: " + results);
        return results;
    }

    /** *************************************************************
     * @return a list of strings in the format sumo(Class,word-num) or
     * sumoInstance(Inst,word-num) that specify the SUMO class of each
     * dismabiguated word or multi-word
     */
    public static List<String> findWSD(List<CoreLabel> sent) {

        boolean debug = true;
        if (debug) System.out.println("INFO in Interpreter.findWSD(): sentence: " + sent);
        KB kb = KBmanager.getMgr().getKB("SUMO");
        Set<String> results = Sets.newHashSet();
        String sumo = null;
        for (CoreLabel cl : sent) {
            String nerType = cl.get(CoreAnnotations.NamedEntityTagAnnotation.class);
            if (debug) System.out.println("INFO in Interpreter.findWSD(): token: " + cl);
            String sexAttribute = cl.get(MachineReadingAnnotations.GenderAnnotation.class);
            if (debug) System.out.println("INFO in Interpreter.findWSD(): Stanford gender: " +
                    sexAttribute);
            if (debug) System.out.println("INFO in Interpreter.findWSD(): Sigma gender: " +
                    getGenderAttribute(cl.originalText()));
            sumo = cl.get(WNMultiWordAnnotator.WNMWSUMOAnnotation.class);
            if (debug) System.out.println("INFO in Interpreter.findWSD(): MW sumo: " +
                    sumo);
            if (!StringUtil.emptyString(sumo)) {   // -----------------------MultiWords (from WordNet)
                String token = cl.get(WNMultiWordAnnotator.WNMWTokenAnnotation.class);
                if (kb.isInstance(sumo)) {
                    if (debug) System.out.println("INFO in Interpreter.findWSD(): instance:  " + sumo);
                    results.add("sumoInstance(" + sumo + "," + token + ")");
                }
                else
                    results.add("sumo(" + sumo + "," + token + ")");
            }
            else if (!StringUtil.emptyString(sexAttribute) || // --------- Gendered names
                    nerType.equals("PERSON") ||
                    getGenderAttribute(cl.originalText()) != "") {
                results.add("sumo(Human," + cl + ")");
                results.add("names(" + cl + ",\"" + cl.originalText() + "\")");
                if (!StringUtil.emptyString(sexAttribute))
                    results.add("attribute(" + cl + "," + sexAttribute + ")");
                else if (!StringUtil.emptyString(getGenderAttribute(cl.originalText())))
                    results.add("attribute(" + cl + "," + getGenderAttribute(cl.originalText()) + ")");
            }
            else {
                sumo = cl.get(WSDAnnotator.SUMOAnnotation.class); // ----------other word senses
                if (debug) System.out.println("INFO in Interpreter.findWSD(): sumo: " +
                        sumo);
                if (!StringUtil.emptyString(sumo) && !WordNet.wn.stopwords.contains(cl.originalText()) &&
                        !qwords.contains(cl.originalText().toLowerCase()) && !excluded(cl.originalText())) {
                    if (kb.isInstance(sumo)) {
                        if (debug) System.out.println("INFO in Interpreter.findWSD(): instance:  " + sumo);
                        results.add("sumoInstance(" + sumo + "," + cl + ")");
                    }
                    else
                        results.add("sumo(" + sumo + "," + cl + ")");
                }
            }
        }
        if (debug) System.out.println("INFO in Interpreter.findWSD(): " + results);
        //results.addAll(clauses);
        return Lists.newArrayList(results);
    }

    /** *************************************************************
     * @return a list of strings in the format sumo(Class,word-num) or
     * sumoInstance(Inst,word-num) that specify the SUMO class of each
     * word that isn't a stopword.

    public static List<String> findWSD(List<String> clauses, Map<String, String> posMap, EntityTypeParser etp) {

        boolean debug = true;
        if (debug) System.out.println("INFO in Interpreter.findWSD(): " + clauses);
        KB kb = KBmanager.getMgr().getKB("SUMO");
        DependencyConverter.readFirstNames();

        Set<String> results = Sets.newHashSet();

        HashMap<String,String> purewords = extractWords(clauses);
        ArrayList<String> pure = Lists.newArrayList(purewords.keySet());
        if (debug) System.out.println("INFO in Interpreter.findWSD(): words: " + pure);
        for (Map.Entry<String, String> pureWordEntry : purewords.entrySet()) {
            String clauseKey = pureWordEntry.getKey();
            String pureWord = pureWordEntry.getValue();
            if (debug) System.out.println("INFO in Interpreter.findWSD(): pureWord: " + pureWord);
            if (WordNet.wn.stopwords.contains(pureWord) ||
                    qwords.contains(pureWord.toLowerCase()) ||
                    excluded(pureWord))
                continue;
            if (etp.equalsToEntityType(clauseKey, PERSON)) {
                String[] split = pureWord.split("_");
                String humanReadable = String.join(" ", split);

                results.add("names(" + clauseKey + ",\"" + humanReadable + "\")");

                Set<String> wordNetResults = ImmutableSet.of();
                if (split.length > 1) {
                    wordNetResults = findWordNetResults(pureWord , clauseKey);
                    results.addAll(wordNetResults);
                }

                if (wordNetResults.isEmpty()) {
                    results.add("sumo(Human," + clauseKey + ")");
                    String sexAttribute = getGenderAttribute(split[0]);
                    if (!sexAttribute.isEmpty()) {
                        results.add("attribute(" + clauseKey + "," + sexAttribute + ")");
                    }
                }
            }
            else {
                if (debug) System.out.println("INFO in Interpreter.findWSD(): pureWord, pure: " +
                        pureWord + ", " +  pure);
                String pos = posMap.get(clauseKey);
                String id = Strings.isNullOrEmpty(pos)
                        ? WSD.findWordSenseInContext(pureWord, pure)
                        : WSD.findWordSenseInContextWithPos(pureWord, pure, WordNetUtilities.sensePOS(pos),false);
                if (debug) System.out.println("INFO in Interpreter.findWSD(): id: " + id);

                if (!Strings.isNullOrEmpty(id)) {
                    String sumo = WordNetUtilities.getBareSUMOTerm(WordNet.wn.getSUMOMapping(id));
                    //System.out.println("INFO in Interpreter.findWSD(): sumo: " + sumo);
                    if (!Strings.isNullOrEmpty(sumo)) {
                        if (sumo.contains(" ")) {  // TODO: if multiple mappings...
                            sumo = sumo.substring(0,sumo.indexOf(" ")-1);
                        }
                        if (kb.isInstance(sumo)) {
                            if (debug) System.out.println("INFO in Interpreter.findWSD(): instance:  " + sumo);
                            results.add("sumoInstance(" + sumo + "," + clauseKey + ")");
                        }
                        else
                            results.add("sumo(" + sumo + "," + clauseKey + ")");
                    }
                }
                else {
                    Set<String> wordNetResults = findWordNetResults(pureWord, clauseKey);
                    if (debug) System.out.println("INFO in Interpreter.findWSD(): wordnet:  " + wordNetResults);
                    if (!wordNetResults.isEmpty()) {
                        results.addAll(wordNetResults);
                    }
                    else {
                        Collection<EntityType> knownTypes = etp.getEntityTypes(clauseKey);
                        if (!knownTypes.isEmpty()) {
                            String[] split = pureWord.split("_");
                            for (String word : split) {
                                String synset = WSD.getBestDefaultSense(word.replace(" ", "_"));
                                if (!Strings.isNullOrEmpty(synset)) {
                                    String sumo = WordNetUtilities.getBareSUMOTerm(WordNet.wn.getSUMOMapping(synset));
                                    if (!Strings.isNullOrEmpty(sumo)) {
                                        if (sumo.indexOf(" ") > -1) {  // TODO: if multiple mappings...
                                            sumo = sumo.substring(0, sumo.indexOf(" ") - 1);
                                        }
                                        for (EntityType type : knownTypes) {
                                            if (kb.isSubclass(sumo, type.getSumoClass())) {
                                                results.add("sumo(" + sumo + "," + clauseKey + ")");
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (debug) System.out.println("INFO in Interpreter.findWSD(): " + results);
        //results.addAll(clauses);

        return Lists.newArrayList(results);
    } */

    /** *************************************************************
     */
    private static Set<String> findWordNetResults(String pureWord, String valueToAdd) {

        Set<String> results = Sets.newHashSet();
        String synset = WSD.getBestDefaultSense(pureWord.replace(" ", "_"));
        //System.out.println("INFO in Interpreter.findWordNetResults(): synset: " + synset);
        if (!Strings.isNullOrEmpty(synset)) {
            String sumo = WordNetUtilities.getBareSUMOTerm(WordNet.wn.getSUMOMapping(synset));
            //System.out.println("INFO in Interpreter.findWordNetResults(): sumo: " + sumo);
            if (!Strings.isNullOrEmpty(sumo)) {
                if (isInstance(sumo)) {
                    results.add("sumoInstance(" + sumo + "," + valueToAdd + ")");
                }
                else {
                    if (sumo.indexOf(" ") > -1) {  // TODO: if multiple mappings...
                        sumo = sumo.substring(0, sumo.indexOf(" ") - 1);
                    }
                    results.add("sumo(" + sumo + "," + valueToAdd + ")");
                }
            }
            else {
                results.add("sumo(Entity," + valueToAdd + ")");
            }
        }
        return results;
    }

    /** *************************************************************
     */
    private static String getGenderAttribute(String object) {

        // TODO: should be using gender annotator
        // token.get(MachineReadingAnnotations.Gende‌​rAnnotation.class) on a CoreLabel
        if (DependencyConverter.maleNames.contains(object))
            return "Male";
        else if (DependencyConverter.femaleNames.contains(object))
            return "Female";
        else
            return "";
    }

    /** *************************************************************
     * Find all the variables that should be quantified - which are
     * those that have an appended "-num" suffix indicating that it
     * stands for a token from the parser.
     */
    private static ArrayList<String> findQuantification(String form) {

        ArrayList<String> quantified = new ArrayList<String>();
        String pattern = "\\?[A-Za-z0-9_-]+";
        Pattern p = Pattern.compile(pattern);
        Formula f = new Formula(form);
        Set<String> vars = f.collectAllVariables();

        for (String v : vars) {
            if (p.matcher(v).matches())
                quantified.add(v);
        }

        return filterAlreadyQuantifiedVariables(form, quantified);
    }

    /** *************************************************************
     */
    private static ArrayList<String> filterAlreadyQuantifiedVariables(String form, ArrayList<String> vars) {

        ArrayList<String> alreadyQuantifiedVars = new ArrayList<String>();

        String quantifierStart = "(exists (";
        String quantifierEnd = ")";

        int start = -1;
        int end = -1;

        while ((start = form.indexOf(quantifierStart, end)) >= 0) {
            end = form.indexOf(quantifierEnd, start+1);
            String varList = form.substring(start+(quantifierStart.length()), end);
            String[] variables = varList.split(" ");
            for (String variable : variables) {
                alreadyQuantifiedVars.add(variable);
            }
        }

        if (!alreadyQuantifiedVars.isEmpty()) {
            vars.removeAll(alreadyQuantifiedVars);
        }

        return vars;
    }

    /** *************************************************************
     */
    private static String prependQuantifier(ArrayList<String> vars, String form) {

        //System.out.println("INFO in Interpreter.prependQuantifier(): " + vars);
        StringBuffer sb = new StringBuffer();
        if (vars == null || vars.size() < 1)
            return form;
        sb.append("(exists (");
        boolean first = true;
        for (String v : vars) {
            if (!first) {
                sb.append(" ");
            }
            sb.append(v);
            first = false;
        }
        sb.append(") \n");
        sb.append(form);
        sb.append(") \n");
        return sb.toString();
    }

    /** *************************************************************
     */
    private static ArrayList<String> getQueryObjectsFromQuantification(ArrayList<String> quantified) {

        ArrayList<String> queryObjects=new ArrayList<String>();
        String pattern_wh = "(\\?[Hh][Oo][Ww][0-9-_]*)|(\\?[wW][Hh]((en)|(EN)|(ere)|(ERE)|(at)|(AT)|(o)|(O)|(ich)|(ICH))?[0-9-_]*)";
        Pattern p_wh = Pattern.compile(pattern_wh);
        for (String k:quantified) {
            if (p_wh.matcher(k).matches()) {
                queryObjects.add(k);
            }
        }
        if(queryObjects.size()==0){
            pattern_wh="\\?[A-Za-z]";
            p_wh=Pattern.compile(pattern_wh);
            for (String k:quantified) {
                if (p_wh.matcher(k).matches()) {
                    queryObjects.add(k);
                }
            }
        }
        quantified.removeAll(queryObjects);
        return queryObjects;
    }

    /** *************************************************************
     * add wh-  how   words at outer most exists and remove these words from the original quantifier finder
     */
    private static String prependQueryQuantifier(ArrayList<String> queryObjects,String form) {

        StringBuilder sb=new StringBuilder();
        if (queryObjects==null || queryObjects.size()<1)
            return form;
        sb.append("(exists (");
        boolean first = true;
        for (String v : queryObjects) {
            if (!first) {
                sb.append(" ");
            }
            sb.append(v);
            first = false;
        }
        sb.append(") \n");
        sb.append("(forall (?DUMMY) \n");
        sb.append(form);
        sb.append(")) \n");
        return sb.toString();
    }

    /** *************************************************************
     */
    public String addQuantification(String form) {

        ArrayList<String> vars = findQuantification(form);
        if (!question)
            return prependQuantifier(vars, form);
        ArrayList<String> queryObjects=getQueryObjectsFromQuantification(vars);
        String innerKIF = prependQuantifier(vars, form);
        return prependQueryQuantifier(queryObjects, innerKIF);
    }

    /** *************************************************************
     */
    private static Formula removeOuterQuantifiers(Formula answer) {

        String head = answer.car();
        if (head != null && head.equals(Formula.EQUANT)) {
            Formula innerFormula = new Formula(answer.caddr());
            if (innerFormula != null) {
                head = innerFormula.car();
                if (head != null && head.equals(Formula.UQUANT)) {
                    return new Formula(innerFormula.caddr());
                }
            }
        }
        return null;
    }

    /** *************************************************************
     */
    private static boolean isOuterQuantified(Formula query) {

        String head = query.car();
        if (head != null && head.equals(Formula.EQUANT)) {
            Formula innerFormula = new Formula(query.caddr());
            if (innerFormula != null) {
                head = innerFormula.car();
                if (head != null && head.equals(Formula.UQUANT)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** *************************************************************
     */
    public String toFOL(ArrayList<String> clauses) {

        StringBuilder sb = new StringBuilder();
        if (clauses.size() > 1)
            sb.append("(and \n");
        for (int i = 0; i < clauses.size(); i++) {
            sb.append("  " + clauses.get(i));
            if (i < clauses.size()-1)
                sb.append("\n");
        }
        if (clauses.size() > 1)
            sb.append(")\n");
        return sb.toString();
    }

    /** *************************************************************
     * Remove prefacing politness expressions.
     */
    public String removePoliteness(String input) {

        String newString = input;
        String m = "(I want to|please)";
        return input.replaceFirst(m,"");
    }

    /** *************************************************************
     * Take in a any number of sentences and return kif strings of declaratives
     * or answer to questions.
     */
    public List<String> interpret(String input) {

        if (StringUtil.emptyString(input))
            return null;
        firedRules = new ArrayList<String>();
        input = input.replaceAll("[^#\\.\\,a-zA-Z0-9\\?\\'_\\-\\–\\(\\)\\: ]","");
        List<String> results = Lists.newArrayList();
        if (!ENDING_IN_PUNC_PATTERN.matcher(input).find()) {
            input = input + ".";
        }
        Annotation wholeDocument = new Annotation(input);
        wholeDocument.set(CoreAnnotations.DocDateAnnotation.class, "2017-05-08");
        p.pipeline.annotate(wholeDocument);

        //Annotation document = Pipeline.toAnnotation(input);
        List<CoreMap> sentences = wholeDocument.get(SentencesAnnotation.class);
        System.out.println("Interpreter.interpret(): Interpreting " + sentences.size() + " inputs.");
        System.out.println("Interpreter.interpret(): original: " + sentences.size() + " inputs.");
        for (CoreMap sentence : sentences) {
            String textSent = sentence.get(CoreAnnotations.TextAnnotation.class);
            System.out.println("Interpreter.interpret(): original: " + textSent);
            if (removePolite)
                textSent = removePoliteness(textSent);
            String interpreted = interpretSingle(textSent);
            results.add(interpreted);
        }
        return results;
    }

    /** *************************************************************
     * Take in a single sentence and output CNF for further processing.
     * Just create the dependency parse - no tense, number, part of
     * speech, coref etc.
     */
    public CNF interpretGenCNFBasic(String input) {

        List<String> results = Lists.newArrayList();
        Annotation wholeDocument = userInputs.annotateDocument(input);
        CoreMap lastSentence = SentenceUtil.getLastSentence(wholeDocument);
        List<String> dependenciesList = SentenceUtil.toDependenciesList(ImmutableList.of(lastSentence));
        results.addAll(dependenciesList);
        String in = StringUtil.removeEnclosingCharPair(results.toString(), Integer.MAX_VALUE, '[', ']');
        Lexer lex = new Lexer(in);
        CNF cnf = CNF.parseSimple(lex);
        return cnf;
    }

    /** *************************************************************
     * Do coreference substitution on string input
     */
    public static String corefSubst(List<String> input) {

        Annotation document = Pipeline.toAnnotation(String.join(" ", input));
        CoreMap lastSentence = SentenceUtil.getLastSentence(document);
        List<CoreLabel> lastSentenceTokens = lastSentence.get(CoreAnnotations.TokensAnnotation.class);
        ClauseSubstitutor substitutor = SubstitutorsUnion.of(
                new CorefSubstitutor(document),
                new NounSubstitutor(lastSentenceTokens),
                new IdiomSubstitutor(lastSentenceTokens)
        );
        SentenceBuilder sb = new SentenceBuilder(lastSentence);
        String actual = String.join(" ", sb.asStrings(substitutor));
        actual = actual.replaceAll("_"," ");
        return actual;
    }

    /** *************************************************************
     * Collect all the identified spans and replace them with single
     * tokens. Remove Literals that are within a span.
     */
    private static List<String> consolidateSpans(List<CoreLabel> lastSentenceTokens, List<String> literals) {

        List<String> result = new ArrayList<>();
        // span indices, new token, old tokens
        // replace all old tokens with new token, delete relations within a span
        int[] spanTotal = new int[lastSentenceTokens.size()]; // start at 1 and use token number indexes
        Arrays.fill(spanTotal,0);
        HashMap<IntPair,String> spans = new HashMap<>(); // span dimensions and token
        HashMap<String,String> sumoMap = new HashMap<>(); // token name to sumo term
        //collectSpans(lastSentenceTokens,spanTotal,spans,sumoMap);
        //replaceSpans(spanTotal,spans,sumoMap,results);
        for (String s : literals) {
            Literal l = new Literal(s);
            //System.out.println("Interpreter.consolidateSpans(): literal: " + l);
            IntPair nerspan = null;
            IntPair wnspan = null;
            IntPair tok1span = null;

            int arg1tok = Literal.tokenNum(l.arg1);
            String arg1 = l.arg1;
            if (arg1tok > 0) {
                CoreLabel token1 = lastSentenceTokens.get(arg1tok-1);
                nerspan = token1.get(NERAnnotator.NERSpanAnnotation.class);
                wnspan = token1.get(WNMultiWordAnnotator.WNMWSpanAnnotation.class);
                //System.out.println("Interpreter.consolidateSpans(): token1: "
                //        + token1 + " nerspan: " + nerspan + " wnspan: " + wnspan);
                if (nerspan != null && wnspan != null)
                    System.out.println("Interpreter.consolidateSpans(): span conflict at: " + token1);
                if (wnspan != null) { // prefer spans from known WordNet multiwords
                    String tok1name = token1.get(WNMultiWordAnnotator.WNMWTokenAnnotation.class);
                    arg1 = tok1name;
                    tok1span = token1.get(WNMultiWordAnnotator.WNMWSpanAnnotation.class);
                }
                else if (nerspan != null) {
                    String tok1name = token1.get(NERAnnotator.NERTokenAnnotation.class);
                    arg1 = tok1name;
                    tok1span = token1.get(NERAnnotator.NERSpanAnnotation.class);
                }
            }

            IntPair tok2span = null;
            String arg2 = l.arg2;
            int arg2tok = Literal.tokenNum(l.arg2);
            if (arg2tok > 0) {
                CoreLabel token2 = lastSentenceTokens.get(arg2tok-1);
                nerspan = token2.get(NERAnnotator.NERSpanAnnotation.class);
                wnspan = token2.get(WNMultiWordAnnotator.WNMWSpanAnnotation.class);
                //System.out.println("Interpreter.consolidateSpans(): token2: "
                //        + token2 + " nerspan: " + nerspan + " wnspan: " + wnspan);
                if (nerspan != null && wnspan != null)
                    System.out.println("Interpreter.consolidateSpans(): span conflict at: " + token2);
                if (wnspan != null) { // prefer spans from known WordNet multiwords
                    String tok2name = token2.get(WNMultiWordAnnotator.WNMWTokenAnnotation.class);
                    arg2 = tok2name;
                    tok2span = token2.get(WNMultiWordAnnotator.WNMWSpanAnnotation.class);
                }
                else if (nerspan != null) {
                    String tok2name = token2.get(NERAnnotator.NERTokenAnnotation.class);
                    arg2 = tok2name;
                    tok2span = token2.get(NERAnnotator.NERSpanAnnotation.class);
                }
            }
            if ((tok1span != null && tok2span != null && tok1span == tok2span) || arg1.equals(arg2))
                continue; // clauses in the same span are ignored
            else {
                l.arg1 = arg1;
                l.arg2 = arg2;
                //System.out.println("Interpreter.consolidateSpans(): new literal: " + l);
                result.add(l.toString());
            }
        }
        //System.out.println("Interpreter.consolidateSpans(): results: " + result);
        return result;
    }

    /** *************************************************************
     * Take in a single sentence and output CNF for further processing.
     */
    public List<String> scrubMeasures(List<String> depClauses, CoreMap lastSentence) {

        List<String> results =  new ArrayList<>();
        List<CoreLabel> labels = lastSentence.get(CoreAnnotations.TokensAnnotation.class);
        List<CoreLabel> lastSentenceTokens = lastSentence.get(CoreAnnotations.TokensAnnotation.class);
        for (String literal : depClauses) {
            Literal l = new Literal(literal);
            int arg1tok = Literal.tokenNum(l.arg1);
            int arg2tok = Literal.tokenNum(l.arg2);
            boolean foundNumber = false;
            if (arg1tok >= 0) {
                CoreLabel token = labels.get(arg1tok);
                String NERtag = token.get(CoreAnnotations.NamedEntityTagAnnotation.class);
                if (NERtag.equals("DATE") || NERtag.equals("NUMBER") || NERtag.equals("ORDINAL") ||
                        NERtag.equals("PERCENT") || NERtag.equals("DURATION") || NERtag.equals("TIME"))
                    foundNumber = true;
            }
            if (arg2tok >= 0) {
                CoreLabel token = labels.get(arg2tok);
                String NERtag = token.get(CoreAnnotations.NamedEntityTagAnnotation.class);
                if (NERtag.equals("DATE") || NERtag.equals("NUMBER") || NERtag.equals("ORDINAL") ||
                        NERtag.equals("PERCENT") || NERtag.equals("DURATION") || NERtag.equals("TIME"))
                    foundNumber = true;
            }
            if (!foundNumber)
                results.add(literal);
        }
        return results;
    }

    /** *************************************************************
     * Take in a single sentence and output CNF for further processing.
     */
    public CNF interpretGenCNF(String input) {

        Annotation wholeDocument = userInputs.annotateDocument(input);
        //removeEndPunc(wholeDocument);
        //System.out.println("Interpreter.interpretGenCNF(): Interpreting " + wholeDocument.size() + " inputs.");
        //System.out.println("Interpreter.interpretGenCNF(): coref chains");
        SentenceUtil.printCorefChain(wholeDocument);
        CoreMap lastSentence = SentenceUtil.getLastSentence(wholeDocument);
        List<CoreLabel> lastSentenceTokens = lastSentence.get(CoreAnnotations.TokensAnnotation.class);

        if (verboseParse) {
            for (CoreLabel cl : lastSentenceTokens)
                System.out.println(cl.originalText() + ": " + cl.ner());
        }
        List<String> results = Lists.newArrayList();
        List<String> dependenciesList = SentenceUtil.toDependenciesList(ImmutableList.of(lastSentence));
        //System.out.println("Interpreter.interpretGenCNF(): dependencies: " + dependenciesList);
        results.addAll(dependenciesList);
        //System.out.println("Interpreter.interpretGenCNF(): before numerics: " + results);

        List<String> wsd = findWSD(lastSentenceTokens);
        results.addAll(wsd);
        //System.out.println("Interpreter.interpretGenCNF(): before consolidate: " + results);
        results = consolidateSpans(lastSentenceTokens,results);
        //System.out.println("Interpreter.interpretGenCNF(): after consolidate: " + results);
        results = replaceInstances(results);
        System.out.println("Interpreter.interpretGenCNF(): after instance replacement: " + results);

        List<String> posInformation = SentenceUtil.findPOSInformation(lastSentenceTokens, dependenciesList);
        // TODO: This is not the best way to substitute POS information
        //posInformation = SubstitutionUtil.groupClauses(substitutor, posInformation);
        results.addAll(posInformation);

        DateAndNumbersGeneration generator = new DateAndNumbersGeneration();
        StanfordDateTimeExtractor sde = new StanfordDateTimeExtractor();
        List<Tokens> tokenList = new ArrayList<Tokens>();
        sde.populateParserInfo(lastSentence,tokenList);
        List<String> timeResults = generator.generateSumoTerms(tokenList, sde);
        results = scrubMeasures(results,lastSentence); // remove original date/time/measure literals
        results.addAll(timeResults);
        //results = lemmatizeResults(results, lastSentenceTokens, substitutor);
        augmentedClauses = new ArrayList<String>();
        augmentedClauses.addAll(results);
//        results = processPhrasalVerbs(results);
        String in = StringUtil.removeEnclosingCharPair(results.toString(),Integer.MAX_VALUE,'[',']');
        Lexer lex = new Lexer(in);
        CNF cnf = CNF.parseSimple(lex);
        System.out.println("Interpreter.interpretGenCNF(): cnf: " + cnf);
        return cnf;
    }

    /** *************************************************************
     * Take in a single sentence and output an English answer.
     * Sets question instance field based on input.
     * Updates tfidf static field with input if input is not question.
     * If first attempt using CNF clauses fails, tries tfidf for questions.
     */
    public String interpretSingle(String input) {

        if (input.trim().endsWith("?") && inference)
            question = true;
        else
            question = false;
        if (!question)
            tfidf.addInput(input);

        Graph g = null;
        ArrayList<CNF> inputs = Lists.newArrayList(interpretGenCNF(input));
        if (simFlood) {
            inputs = Lists.newArrayList(interpretGenCNFBasic(input));
            for (CNF cnf : inputs) {
                g = new Graph(input);
                g.fromCNF(cnf);
                if (!question)
                    userGraphs.add(g);
            }
        }
        ArrayList<String> kifClauses = interpretCNF(inputs);
        String result = fromKIFClauses(kifClauses);
        System.out.println("INFO in Interpreter.interpretSingle(): Theorem proving result: '" + result + "'\n");

        if (question) {
            if (simFlood) {
                SimFlood sf = new SimFlood();
                result = sf.match(g, userGraphs).toString();
            }
            if ((ANSWER_UNDEFINED.equals(result) && autoir) || ir) {
                if (autoir)
                    System.out.println("Interpreter had no response so trying TFIDF");
                result = tfidf.matchInput(input).toString();
            }
        }
        else {
            userInputs.add(input); // Store processed sentence
        }
        //System.out.println("INFO in Interpreter.interpretSingle(): combined result: " + result);
        return result;
    }

    /** *************************************************************
     * Method (mainly for testing) to get list of CNFs from input sentence.
     * @param input string representing input sentence
     * @return list of CNFs
     */
    protected ArrayList<CNF> getCNFInput(String input) {

        Lexer lex = new Lexer(input);
        CNF cnf = CNF.parseSimple(lex);
        ArrayList<CNF> cnfInput = new ArrayList<CNF>();
        cnfInput.add(cnf);
        return cnfInput;
    }

    /** *************************************************************
     * Combine phrasal verbs in dependency parsing results.
     */
    public static List<String> processPhrasalVerbs(List<String> results) {

        if (!containsPhrasalVerbs(results))
            return results;
        String verb = null;
        String particle = null;
        String[] elems;

        for (String dependency : results) {
            if (dependency.startsWith(PHRASAL_VERB_PARTICLE_TAG)) {
                int index = (PHRASAL_VERB_PARTICLE_TAG).length();
                String verbAndParticle = dependency.substring(index, dependency.length()-1);
                elems = verbAndParticle.split(",");
                verb = elems[0].trim();
                particle = elems[1].trim();
                break;
            }
        }
        if (null == verb || null == particle)
            return results;

        elems = verb.split("-");
        String verbWord = elems[0];
        int verbNum = Integer.parseInt(elems[1]);

        elems = particle.split("-");
        String particleWord = elems[0];

        String phrasalVerb = verbWord + "-" + particleWord + "-" + verbNum;

        List<String> newResults = Lists.newArrayList();

        for (String dependency : results) {
            if (!dependency.startsWith(PHRASAL_VERB_PARTICLE_TAG) &&
                    !(dependency.startsWith(SUMO_TAG) && dependency.contains(verb))) {
//                String newDependency = modifyDependencyElem(dependency, verbNum);
                String newDependency = dependency;
                if (newDependency.contains(verb)) {
                    newDependency = newDependency.replace(verb, phrasalVerb);
                }
                newResults.add(newDependency);
            }
        }
        return newResults;
    }

    /** *************************************************************
     */
    private static boolean containsPhrasalVerbs(List<String> results) {

        for (String dependency : results) {
            if (dependency.startsWith(PHRASAL_VERB_PARTICLE_TAG)) {
                return true;
            }
        }
        return false;
    }

    /** *************************************************************
     */
    private static String modifyDependencyElem(String dependency, int verbNum) {

        String newDependency = dependency;
        int index = newDependency.indexOf("(");
        String dependencyElems = newDependency.substring(index+1, newDependency.length()-1);

        String[] elems = dependencyElems.split(",");

        String elem;
        String newElem;
        String[] subElems;
        int subElemNum;

        if (!newDependency.startsWith(SUMO_TAG) && !newDependency.startsWith(TENSE_TAG) &&
                !newDependency.startsWith(NUMBER_TAG)) {
            elem = elems[0].trim();
            subElems = elem.split("-");
            subElemNum = Integer.parseInt(subElems[1]);
            if (subElemNum > verbNum) {
                subElemNum--;
            }
            newElem = subElems[0] + "-" + subElemNum;
            newDependency = newDependency.replace(elem, newElem);
        }

        elem = elems[1].trim();
        subElems = elem.split("-");
        subElemNum = Integer.parseInt(subElems[1]);
        if (subElemNum > verbNum) {
            subElemNum--;
        }
        newElem = subElems[0] + "-" + subElemNum;
        newDependency = newDependency.replace(elem, newElem);

        return newDependency;
    }

    /** *************************************************************
     * Lemmatize the results of the dependency parser, WSD, etc.
     */
    public static List<String> lemmatizeResults(List<String> results,
                                                List<CoreLabel> tokens, ClauseSubstitutor substitutor) {

        List<String> lemmatizeResults = Lists.newArrayList(results);

        for (CoreLabel label : tokens) {
            if (!"NNP".equals(label.tag()) && !"NNPS".equals(label.tag())) {
                CoreLabelSequence grouped = substitutor.getGroupedByFirstLabel(label).orElse(new CoreLabelSequence(label));
                String replace = grouped.toLabelString().get();
                String replaceTo = Joiner.on("_").join(
                        grouped.getLabels().stream()
                                .map(l -> l.lemma()).toArray())
                        + "-" + grouped.getLabels().get(0).index();

                for (String singleResult : ImmutableList.copyOf(lemmatizeResults)) {
                    if (singleResult.contains(replace)) {
                        lemmatizeResults.remove(singleResult);
                        lemmatizeResults.add(singleResult.replace(replace, replaceTo));
                    }
                }
            }
        }
        return lemmatizeResults;
    }

    /** *************************************************************
     * @param tokens - List of CoreLabel tokens representing a sentence/input
     * @return Map of token position -> POS
     * ex.  Mary-1 -> NNP
     *      drives-2 -> VBZ
     *      the-3 -> DT
     *      car-4 -> NN
     */
    private static Map<String, String> getPartOfSpeechList(List<CoreLabel> tokens, ClauseSubstitutor substitutor) {

        Map<String, String> posMap = Maps.newHashMap();
        for (CoreLabel token : tokens) {
            CoreLabelSequence seq = substitutor.containsKey(token)
                    ? substitutor.getGrouped(token)
                    : CoreLabelSequence.from(token);
            for (CoreLabel label : seq.getLabels()) {
                posMap.put(label.originalText() + "-" + label.index(), label.tag());
            }
        }
        return posMap;
    }

    /** *************************************************************
     */
    private void printLabel(CoreLabel label) {

        System.out.println(label.get(CoreAnnotations.ValueAnnotation.class) + " " + label.get(CoreAnnotations.PartOfSpeechAnnotation.class));
    }

    /** *************************************************************
     */
    public String printKB(ArrayList<CNF> inputs) {

        StringBuilder sb = new StringBuilder();
        sb.append("\n------------------------------\n");
        for (int i = 0; i < inputs.size(); i++)
            sb.append(inputs.get(i).toString() + ".\n");
        sb.append("------------------------------\n");
        return sb.toString();
    }

    /** *************************************************************
     */
    private static boolean isInstance(String s) {

        String noSuffix = stripSuffix(s);
        //System.out.println("Info in Interpreter.isInstance(): " + noSuffix);

        KB kb = KBmanager.getMgr().getKB("SUMO");
        //System.out.println("Info in Interpreter.isInstance(): " + kb.isInstance(noSuffix));
        return kb.isInstance(noSuffix);
    }

    /** *************************************************************
     */
    public static String postProcess(String s) {

        //System.out.println("Info in Interpreter.postProcess(): " + s);
        String pattern = "([^\\?A-Za-z])([A-Za-z0-9_]+\\-[0-9]+)";
        Pattern p = Pattern.compile(pattern);
        Matcher matcher = p.matcher(s);
        while (matcher.find()) {
            if (!isInstance(matcher.group(2)))
                s = s.replace(matcher.group(1) + matcher.group(2), matcher.group(1) + "?" + matcher.group(2));
        }
        //System.out.println("Info in Interpreter.postProcess(): after: " + s);
        Formula f = new Formula(s);
        return s;
    }

    /** *************************************************************
     */
    public static void preProcessQuestionWords(CNF inputs) {

        //List<String> qphrase = Lists.newArrayList("how much","how many","how often","how far","how come");
        inputs.preProcessQuestionWords(qwords);
    }

    /** *************************************************************
     */
    public static void addUnprocessed(ArrayList<String> kifoutput, CNF cnf) {

        StringBuilder sb = new StringBuilder();
        for (Clause d : cnf.clauses) {
            if (d.disjuncts.size() > 1)
                sb.append("(or \n");
            for (Literal c : d.disjuncts) {
                kifoutput.add("(" + c.pred + " " + c.arg1  + " " + c.arg2 + ") ");
            }
            if (d.disjuncts.size() > 1)
                sb.append(")\n");
        }
        kifoutput.add(sb.toString());
    }

    /** *************************************************************
     */
    public ArrayList<String> interpretCNF(ArrayList<CNF> inputs) {

        if (inputs.size() > 1) {
            System.out.println("Error in Interpreter.interpretCNF(): multiple clauses");
            return null;
        }
        ArrayList<String> kifoutput = new ArrayList<String>();
        System.out.println("INFO in Interpreter.interpretCNF(): inputs: " + inputs);
        boolean bindingFound = true;
        int counter = 0;
        while (bindingFound && counter < 10 && inputs != null && inputs.size() > 0) {
            counter++;
            bindingFound = false;
            ArrayList<CNF> newinputs = new ArrayList<CNF>();
            CNF newInput = null;
            for (int j = 0; j < inputs.size(); j++) {
                newInput = inputs.get(j).deepCopy();
                //System.out.println("INFO in Interpreter.interpretCNF(): new input 0: " + newInput);
                for (int i = 0; i < rs.rules.size(); i++) {
                    Rule r = rs.rules.get(i).deepCopy();
                    //System.out.println("INFO in Interpreter.interpretCNF(): new input 0.5: " + newInput);
                    if (debug) System.out.println("INFO in Interpreter.interpretCNF(): r: " + r);
                    HashMap<String,String> bindings = r.cnf.unify(newInput);
                    if (bindings == null) {
                        newInput.clearBound();
                    }
                    else {
                        bindingFound = true;
                        //System.out.println("INFO in Interpreter.interpretCNF(): new input 1: " + newInput);
                        if (debug) System.out.println("INFO in Interpreter.interpretCNF(): bindings: " + bindings);
                        if (showr)
                            System.out.println("INFO in Interpreter.interpretCNF(): r: " + r);
                        firedRules.add(r.toString());
                        RHS rhs = r.rhs.applyBindings(bindings);
                        if (r.operator == Rule.RuleOp.IMP) {
                            CNF bindingsRemoved = newInput.removeBound(); // delete the bound clauses
                            //System.out.println("INFO in Interpreter.interpretCNF(): input with bindings removed: " + bindingsRemoved);
                            if (!bindingsRemoved.empty()) {  // assert the input after removing bindings
                                if (rhs.cnf != null) {
                                    if (showrhs)
                                        System.out.println("   " + rhs.cnf);
                                    bindingsRemoved.merge(rhs.cnf);
                                }
                                newInput = bindingsRemoved;
                            }
                            else
                            if (rhs.cnf != null) {
                                if (showrhs)
                                    System.out.println("   " + rhs.cnf);
                                newInput = rhs.cnf;
                            }
                            if (rhs.form != null && !kifoutput.contains(rhs.form.toString())) { // assert a KIF RHS
                                if (showrhs)
                                    System.out.println("   " + rhs.form);
                                kifoutput.add(rhs.form.toString());
                            }
                            //System.out.println("INFO in Interpreter.interpretCNF(): new input 2: " + newInput + "\n");
                        }
                        else if (r.operator == Rule.RuleOp.OPT) {
                            CNF bindingsRemoved = newInput.removeBound(); // delete the bound clauses
                            if (!bindingsRemoved.empty() && !newinputs.contains(bindingsRemoved)) {  // assert the input after removing bindings
                                if (rhs.cnf != null)
                                    bindingsRemoved.merge(rhs.cnf);
                                newinputs.add(bindingsRemoved);
                            }
                            if (rhs.form != null && !kifoutput.contains(rhs.form.toString())) { // assert a KIF RHS
                                if (showrhs)
                                    System.out.println("   " + rhs.form);
                                kifoutput.add(rhs.form.toString());
                            }
                        }
                        else                                                                         // empty RHS
                            newInput.clearBound();
                    }
                    newInput.clearBound();
                    newInput.clearPreserve();
                }
            }
            if (bindingFound)
                newinputs.add(newInput);
            else
            if (addUnprocessed)
                addUnprocessed(kifoutput,newInput); // a hack to add unprocessed SDP clauses as if they were KIF
            inputs = new ArrayList<CNF>();
            inputs.addAll(newinputs);
            //System.out.println("INFO in Interpreter.interpretCNF(): KB: " + printKB(inputs));
            //System.out.println("INFO in Interpreter.interpretCNF(): KIF: " + kifoutput);
            //System.out.println("INFO in Interpreter.interpretCNF(): bindingFound: " + bindingFound);
            //System.out.println("INFO in Interpreter.interpretCNF(): counter: " + counter);
            //System.out.println("INFO in Interpreter.interpretCNF(): newinputs: " + newinputs);
            System.out.println("INFO in Interpreter.interpretCNF(): inputs: " + inputs);
        }
        return kifoutput;
    }

    /** ***************************************************************
     * @param kifcs a list of String simple KIF clauses
     * @return the response from the E prover, whether an acknowledgement
     * of an assertion, or a formula with the answer bindings substituted in
     */
    public String fromKIFClauses(ArrayList<String> kifcs) {

        String s1 = toFOL(kifcs);
        //System.out.println("INFO in Interpreter.fromKIFClauses(): toFOL: " + s1);
        String s2 = postProcess(s1);
        //System.out.println("INFO in Interpreter.fromKIFClauses(): postProcessed: " + s2);
        String s3 = addQuantification(s2);
        //System.out.println("INFO in Interpreter.fromKIFClauses(): KIF: " + (new Formula(s3)));
        if (inference) {
            KB kb = KBmanager.getMgr().getKB("SUMO");
            if (question) {
                Formula query = new Formula(s3);
                ArrayList<String> inferenceAnswers = Lists.newArrayList();
                if (verboseProof) {
                    inferenceAnswers = kb.ask(s3, timeOut_value, 1);
                }
                else {
                    inferenceAnswers = kb.askNoProof(s3, timeOut_value, 1);
                }
                if (verboseAnswer) {
                    System.out.println("Inference Answers: " + inferenceAnswers);
                }

                String answer = Interpreter.formatAnswer(query, inferenceAnswers, kb);
                System.out.println(answer);
                return answer;
            }
            else {
                System.out.println(kb.tell(s3));
            }
        }
        return Formula.textFormat(s3);
    }

    /** ***********************************************************************************
     * generates the answer to a query by replacing the variables with the results from the inference call
     */
    public static String formatAnswer(Formula query, List<String> inferenceAnswers, KB kb) {

        if (inferenceAnswers == null || inferenceAnswers.size() < 1) {
            return ANSWER_UNDEFINED;
        }

        if (Interpreter.isOuterQuantified(query)) {
            try {
                //this NLG code will replace the simplistic answer formatting above, once NLG works properly
                // ATTENTION: when uncommenting the code also remove the call to findTypesForSkolemTerms() in
                // the TPTP3ProofProcessor class which causes problems in replaceQuantifierVars()  below because
                // it replaces the skolem vars with "an instance of blabla" texts
                // Instead, use a new method in TPTP3ProofProcessor which gives you a maping skolem->specific type
                // and use that in NLG, or something similar
//                Formula answer = query.replaceQuantifierVars(Formula.EQUANT, inferenceAnswers);
//                answer = Interpreter.removeOuterQuantifiers(answer);
//                LanguageFormatter lf = new LanguageFormatter(answer.theFormula, kb.getFormatMap("EnglishLanguage"), kb.getTermFormatMap("EnglishLanguage"),
//                        kb, "EnglishLanguage");
//                lf.setDoInformalNLG(true);
//                String actual = lf.htmlParaphrase("");
//                actual = StringUtil.filterHtml(actual);
//                return actual;
                StringBuilder answerBuilder = new StringBuilder();
                int count = 0;
                for (String binding:inferenceAnswers) {
                    count++;
                    answerBuilder.append(binding);
                    if (count < inferenceAnswers.size()) {
                        answerBuilder.append(" and ");
                    }
                    else {
                        answerBuilder.append(".");
                    }
                }
                String answer = answerBuilder.toString();
                return Character.toUpperCase(answer.charAt(0)) + answer.substring(1);
            }
            catch (Exception e) {
                //e.printStackTrace();
                // need proper logging, log4j maybe
                System.out.println(ANSWER_UNDEFINED);
                return ANSWER_UNDEFINED;
            }
        }
        else if (query.isExistentiallyQuantified()) {
            //the query is a yes/no question
            if (inferenceAnswers != null && inferenceAnswers.size() > 0) {
                return ANSWER_YES;
            }
            else {
                return ANSWER_NO;
            }
        }
        else {
            return ANSWER_UNDEFINED;
        }
    }

    /** ***************************************************************
     */
    public void testUnifyInter() {

        String rule = "";
        String facts = "";
        Clause.debug = true;
        Scanner scanner = new Scanner(System.in);
        do {
            System.out.print("Enter rule: ");
            rule = scanner.nextLine().trim();
            if (!StringUtil.emptyString(rule)) {
                System.out.print("Enter facts: ");
                facts = scanner.nextLine().trim();
                if (!StringUtil.emptyString(facts)) {
                    Lexer lex = new Lexer(facts);
                    CNF cnfInput = CNF.parseSimple(lex);
                    Rule r = new Rule();
                    r = Rule.parseString(rule);
                    CNF cnf = Clausifier.clausify(r.lhs);
                    System.out.println("INFO in Interpreter.testUnifyInter(): Input: " + cnfInput);
                    System.out.println("INFO in Interpreter.testUnifyInter(): CNF rule antecedent: " + cnf);
                    HashMap<String, String> bindings = cnf.unify(cnfInput);
                    System.out.println("bindings: " + bindings);
                    System.out.println("result: " + r.rhs.applyBindings(bindings));
                }
            }
        } while (!rule.equals("exit") && !rule.equals("quit"));
    }

    /** ***************************************************************
     * allows interactive testing of entering a rule and seeing if it
     * unifies with a set of literals
     */
    public void interpInter() {

        String input = "";
        Scanner scanner = new Scanner(System.in);
        do {
            System.out.print("Enter sentence: ");
            input = scanner.nextLine().trim();
            if (!Strings.isNullOrEmpty(input) && !input.equals("exit") && !input.equals("quit")) {
                if (input.equals("reload")) {
                    System.out.println("reloading semantic rewriting rules");
                    loadRules();
                }
                else if (input.equals("inference")) {
                    inference = true;
                    System.out.println("turned inference on");
                }
                else if (input.equals("noinference")) {
                    inference = false;
                    System.out.println("turned inference off");
                }
                else if (input.equals("addUnprocessed")) {
                    addUnprocessed = true;
                    System.out.println("adding unprocessed clauses");
                }
                else if (input.equals("noUnprocessed")) {
                    addUnprocessed = false;
                    System.out.println("not adding unprocessed clauses");
                }
                else if (input.equals("noshowr")) {
                    showr = false;
                    System.out.println("not showing rule that are applied");
                }
                else if (input.equals("showr")) {
                    showr = true;
                    System.out.println("showing rules that are applied");
                }
                else if (input.equals("sim")) {
                    simFlood = true;
                    inference = false;
                    System.out.println("using Similarity Flooding no logical inference");
                }
                else if (input.equals("nosim")) {
                    simFlood = false;
                    System.out.println("not using Similarity Flooding");
                }
                else if (input.equals("debug")) {
                    Procedures.debug = true;
                    this.debug = true;
                    System.out.println("debugging messages on");
                }
                else if (input.equals("nodebug")) {
                    Procedures.debug = false;
                    this.debug = false;
                    System.out.println("debugging messages off");
                }
                else if (input.equals("noshowrhs")) {
                    showrhs = false;
                    System.out.println("not showing right hand sides that are asserted");
                }
                else if (input.equals("showrhs")) {
                    showrhs = true;
                    System.out.println("showing right hand sides that are asserted");
                }
                else if (input.equals("ir")) {
                    ir = true;
                    autoir = false;
                    System.out.println("always calling TF/IDF");
                }
                else if (input.equals("noir")) {
                    ir = false;
                    autoir = false;
                    System.out.println("never calling TF/IDF");
                }
                else if (input.equals("autoir")) {
                    autoir = true;
                    System.out.println("call TF/IDF on inference failure");
                }
                else if (input.equals("showproof")) {
                    if (verboseProof) {
                        verboseProof = false;
                    }
                    else {
                        verboseProof = true;
                        verboseAnswer = true;
                    }
                }
                else if (input.equals("inferenceanswer")) {
                    if (verboseAnswer) {
                        verboseAnswer = false;
                    }
                    else {
                        verboseAnswer = true;
                    }
                }
                else if (input.startsWith("reload "))
                    loadRules(input.substring(input.indexOf(' ')+1));
                else if (input.equals("showpos")) {
                    if (verboseParse) {
                        verboseParse = false;
                        System.out.println("STOP: Outputting Part Of Speech information");
                    }
                    else {
                        verboseParse = true;
                        System.out.println("START: Outputting Part Of Speech information");
                    }

                }
                else if (input.startsWith("timeout")) {
                    timeOut_value = Integer.valueOf(input.split(" ")[1]);
                }
                else {
                    System.out.println("INFO in Interpreter.interpretIter(): " + input);
                    List<String> results = interpret(input);
                    int count = 1;
                    for (String result : results) {
                        System.out.println("Result " + count + ": " + result);
                        count++;
                    }
                }
            }
        } while (!input.equals("exit") && !input.equals("quit"));
    }

    /** ***************************************************************
     */
    public void loadRules(String f) {

        if (f.indexOf(File.separator.toString(),2) < 0)
            f = "/home/apease/workspace/sumo/WordNetMappings" + File.separator + f;
        try {
            fname = f;
            RuleSet rsin = RuleSet.readFile(f);
            rs = canon(rsin);
        }
        catch (Exception e) {
            e.printStackTrace();
            System.out.println(e.getMessage());
            return;
        }
        System.out.println("INFO in Interpreter.loadRules(): " +
                rs.rules.size() + " rules loaded from " + f);
    }

    /** ***************************************************************
     */
    public void loadRules() {

       // String filename = KBmanager.getMgr().getPref("kbDir") + File.separator +
               // "WordNetMappings" + File.separator + "SemRewrite.txt";
        String filename = "/home/apease/workspace/sumo/WordNetMappings" + File.separator + "SemRewrite.txt";
        String pref = KBmanager.getMgr().getPref("SemRewrite");
        if (!Strings.isNullOrEmpty(pref))
            filename = pref;
        loadRules(filename);
    }

    /** ***************************************************************
     */
    public void initialize() throws IOException {

        loadRules();
        tfidf = new TFIDF(KBmanager.getMgr().getPref("kbDir") + File.separator +
                "WordNetMappings" + File.separator + "stopwords.txt");
        DependencyConverter.readFirstNames();
    }

    /** ***************************************************************
     */
    public static void testUnify() {

        String input = "sense(212345678,hired-3), det(bank-2, The-1), nsubj(hired-3, bank-2), root(ROOT-0, hired-3), dobj(hired-3, John-4).";
        Lexer lex = new Lexer(input);
        CNF cnfInput = CNF.parseSimple(lex);

        String rule = "sense(212345678,?E) , nsubj(?E,?X) , dobj(?E,?Y) ==> " +
                "{(and " +
                "(instance ?X Organization) " +
                "(instance ?Y Human)" +
                "(instance ?E Hiring)" +
                "(agent ?E ?X) " +
                "(patient ?E ?Y))}.";
        Rule r = new Rule();
        r = Rule.parseString(rule);
        CNF cnf = Clausifier.clausify(r.lhs);
        System.out.println("INFO in Interpreter.testUnify(): Input: " + cnfInput);
        System.out.println("INFO in Interpreter.testUnify(): CNF rule antecedent: " + cnf);
        HashMap<String,String> bindings = cnf.unify(cnfInput);
        System.out.println("bindings: " + bindings);
        System.out.println("result: " + r.rhs.applyBindings(bindings));
    }

    /** ***************************************************************
     */
    public static void testUnify2() {

        String input = "root(ROOT-0,American-4), nsubj(American-4,John-1), cop(American-4,is-2), det(American-4,an-3), " +
                "sumo(UnitedStates,American-4), names(John-1,\"John\"), attribute(John-1,Male), sumo(Human,John-1), " +
                "number(SINGULAR,John-1), tense(PRESENT,is-2).";
        Lexer lex = new Lexer(input);
        CNF cnfInput = CNF.parseSimple(lex);

        //  cop(?C,is*),
        String rule = "nsubj(?C,?X), det(?C,?D), sumo(?Y,?C), isInstance(?Y,Nation) ==> (citizen(?X,?Y)).";
        Rule r = new Rule();
        r = Rule.parseString(rule);
        CNF cnf = Clausifier.clausify(r.lhs);
        System.out.println("INFO in Interpreter.testUnify2(): Input: " + cnfInput);
        System.out.println("INFO in Interpreter.testUnify2(): CNF rule antecedent: " + cnf);
        HashMap<String,String> bindings = cnf.unify(cnfInput);
        System.out.println("bindings: " + bindings);
        if (bindings != null)
            System.out.println("result: " + r.rhs.applyBindings(bindings));
    }

    /** ***************************************************************
     */
    public static void testUnify3() {

        String input = "advmod(die-6,when-1), aux(die-6,do-2),sumo(Death,die-6), nsubj(die-6,AmeliaMaryEarhart-3), sumo(IntentionalProcess,do-2).";
        Lexer lex = new Lexer(input);
        CNF cnfInput = CNF.parseSimple(lex);
        String rule = "advmod(?V,when-1), aux(?V,do*), sumo(?C,?V), +nsubj(?V,?A), sumo(?C2,do*)  ==> {(and (agent ?V ?A) (instance ?V ?C) (equals ?WHEN (WhenFn ?V)}.";
        Rule r = new Rule();
        r = Rule.parseString(rule);
        CNF cnf = Clausifier.clausify(r.lhs);
        System.out.println("INFO in Interpreter.testUnify3(): Input: " + cnfInput);
        System.out.println("INFO in Interpreter.testUnify3(): CNF rule antecedent: " + cnf);
        HashMap<String,String> bindings = cnf.unify(cnfInput);
        System.out.println("bindings: " + bindings);
        if (bindings != null)
            System.out.println("result: " + r.rhs.applyBindings(bindings));
    }

    /** ***************************************************************
     */
    public static void testUnify4() {

        String input = "advmod(die-6,when-1), aux(die-6,do-2),sumo(Death,die-6), nsubj(die-6,AmeliaMaryEarhart-3), sumo(IntentionalProcess,do-2).";
        Lexer lex = new Lexer(input);
        CNF cnfInput = CNF.parseSimple(lex);
        String rule = "advmod(?V,when-1), aux(?V,do*), sumo(?C,?V), +nsubj(?V,?A), -sumo(?C2,do*)  ==> {(and (agent ?V ?A) (instance ?V ?C) (equals ?WHEN (WhenFn ?V)}.";
        Rule r = new Rule();
        r = Rule.parseString(rule);
        CNF cnf = Clausifier.clausify(r.lhs);
        System.out.println("INFO in Interpreter.testUnify4(): Input: " + cnfInput);
        System.out.println("INFO in Interpreter.testUnify4(): CNF rule antecedent: " + cnf);
        HashMap<String,String> bindings = cnf.unify(cnfInput);
        System.out.println("bindings: " + bindings);
        if (bindings != null)
            System.out.println("result: " + r.rhs.applyBindings(bindings));
    }

    /** ***************************************************************
     */
    public static void testUnify5() {

        KBmanager.getMgr().initializeOnce();
        String input = "nmod:on(Terminator-2, Hulu-4), sumo(Terminator,Terminator-2), sumo(HuluProgram,Hulu-4)," +
                " number(SINGULAR, Terminator-2), number(SINGULAR, Hulu-4), root(ROOT-0, watch-1)," +
                " dobj(watch-1, Terminator-2), sumo(Looking,watch-1), case(Hulu-4, on-3).";
        Lexer lex = new Lexer(input);
        CNF cnfInput = CNF.parseSimple(lex);
        //String rule = "nmod:on(?X,?Y), +sumo(?C,?Y), isSubclass(?C,ComputerProgram), +dobj(?V,?X) ==> (instrument(?V,?Y)).";
        String rule =  "nmod:on(?X,?Y),  +dobj(?V,?X), +sumo(?C,?Y), isSubclass(?C,ComputerProgram) ==> (instrument(?V,?Y)).";
        //String rule =  "nmod:on(?X,?Y),  +dobj(?V,?X), +sumo(?C,?Y) ==> (instrument(?V,?Y)).";
        Rule r = new Rule();
        r = Rule.parseString(rule);
        CNF cnf = Clausifier.clausify(r.lhs);
        System.out.println("INFO in Interpreter.testUnify5(): Input: " + cnfInput);
        System.out.println("INFO in Interpreter.testUnify5(): CNF rule antecedent: " + cnf);
        HashMap<String,String> bindings = cnf.unify(cnfInput);
        System.out.println("bindings: " + bindings);
        if (bindings != null)
            System.out.println("result: " + r.rhs.applyBindings(bindings));
    }

    /** ***************************************************************
     */
    public static void testUnify6() {

        KBmanager.getMgr().initializeOnce();
        String input = "root(ROOT-0, walks-2), \n" +
                "nsubj(walks-2, Robert-1), \n" +
                "case(store-5, to-3), \n" +
                "det(store-5, the-4), \n" +
                "nmod:to(walks-2, store-5), sumo(RetailStore,store-5).";
        Lexer lex = new Lexer(input);
        CNF cnfInput = CNF.parseSimple(lex);
        //String rule = "nmod:on(?X,?Y), +sumo(?C,?Y), isSubclassOf(?C,ComputerProgram), +dobj(?V,?X) ==> (instrument(?V,?Y)).";
        String rule =  "nmod:to(?X,?Y), +sumo(?C,?Y), isSubclass(?C,Object) ==> (destination(?V,?Y)).";
        Rule r = new Rule();
        r = Rule.parseString(rule);
        CNF cnf = Clausifier.clausify(r.lhs);
        System.out.println("INFO in Interpreter.testUnify5(): Input: " + cnfInput);
        System.out.println("INFO in Interpreter.testUnify5(): CNF rule antecedent: " + cnf);
        HashMap<String,String> bindings = cnf.unify(cnfInput);
        System.out.println("bindings: " + bindings);
        if (bindings != null)
            System.out.println("result: " + r.rhs.applyBindings(bindings));
    }

    /** ***************************************************************
     */
    public static void testUnify7() {

        KBmanager.getMgr().initializeOnce();
        String input = "dobj(ate-3, chicken-4), sumo(Eating,ate-3), sumo(Man,George-1), nmod:in(ate-3, December-6), compound(Washington-2, George-1), root(ROOT-0, ate-3), sumo(ChickenMeat,chicken-4)";
        Lexer lex = new Lexer(input);
        CNF cnfInput = CNF.parseSimple(lex);
        //String rule = "nmod:on(?X,?Y), +sumo(?C,?Y), isSubclassOf(?C,ComputerProgram), +dobj(?V,?X) ==> (instrument(?V,?Y)).";
        String rule =  "nmod:in(?X,?Y), +sumo(?C,?Y), isCELTclass(?C,Time) ==> (during(?X,?Y)).";
        Rule r = new Rule();
        r = Rule.parseString(rule);
        CNF cnf = Clausifier.clausify(r.lhs);
        System.out.println("INFO in Interpreter.testUnify7(): Input: " + cnfInput);
        System.out.println("INFO in Interpreter.testUnify7(): CNF rule antecedent: " + cnf);
        HashMap<String,String> bindings = cnf.unify(cnfInput);
        System.out.println("bindings: " + bindings);
        if (bindings != null)
            System.out.println("result: " + r.rhs.applyBindings(bindings));
    }

    /** ***************************************************************
     */
    public static void testUnify8() {

        KBmanager.getMgr().initializeOnce();
        String input = "nmod:poss(hat-3,John-1), sumo(Hat,hat-3), sumo(Human,John-1)";
        Lexer lex = new Lexer(input);
        CNF cnfInput = CNF.parseSimple(lex);
        //String rule = "nmod:on(?X,?Y), +sumo(?C,?Y), isSubclassOf(?C,ComputerProgram), +dobj(?V,?X) ==> (instrument(?V,?Y)).";
        String rule =  "nmod:poss(?O,?Y), sumo(Human,?Y), sumo(?C,?O), isCELTclass(?C,Object) ==> {(possesses ?S ?O)}.";
        Rule r = new Rule();
        r = Rule.parseString(rule);
        CNF cnf = Clausifier.clausify(r.lhs);
        cnf.debug = true;
        System.out.println("INFO in Interpreter.testUnify8(): Input: " + cnfInput);
        System.out.println("INFO in Interpreter.testUnify8(): CNF rule antecedent: " + cnf);
        HashMap<String,String> bindings = cnf.unify(cnfInput);
        System.out.println("bindings: " + bindings);
        if (bindings != null)
            System.out.println("result: " + r.rhs.applyBindings(bindings));
    }

    /** ***************************************************************
     */
    public static void testInterpretGenCNF() {

        try {
            KBmanager.getMgr().initializeOnce();
            Interpreter interp = new Interpreter();
            interp.initialize();
            String sent = "Robert kicks the bucket";
            System.out.println("INFO in Interpreter.testInterpret(): " + sent);
            System.out.println(interp.interpretGenCNF(sent));
        }
        catch (Exception e) {
            e.printStackTrace();
            System.out.println(e.getMessage());
        }
    }

    /** ***************************************************************
     */
    public static void testInterpret() {

        try {
            KBmanager.getMgr().initializeOnce();
            Interpreter interp = new Interpreter();
            interp.initialize();
            String sent = "John walks to the store.";
            System.out.println("INFO in Interpreter.testInterpret(): " + sent);
            String input = "nsubj(runs-2,John-1), root(ROOT-0,runs-2), det(store-5,the-4), prep_to(runs-2,store-5), sumo(Human,John-1), attribute(John-1,Male), sumo(RetailStore,store-5), sumo(Running,runs-2).";
            Lexer lex = new Lexer(input);
            CNF cnfInput = CNF.parseSimple(lex);
            ArrayList<CNF> inputs = new ArrayList<CNF>();
            inputs.add(cnfInput);
            System.out.println(interp.interpretCNF(inputs));

            sent = "John takes a walk.";
            System.out.println("INFO in Interpreter.testInterpret(): " + sent);
            input = "nsubj(takes-2,John-1), root(ROOT-0,takes-2), det(walk-4,a-3), dobj(takes-2,walk-4), sumo(Human,John-1), attribute(John-1,Male), sumo(agent,takes-2), sumo(Walking,walk-4).";
            lex = new Lexer(input);
            cnfInput = CNF.parseSimple(lex);
            inputs = new ArrayList<CNF>();
            inputs.add(cnfInput);
            System.out.println(interp.interpretCNF(inputs));

            sent = "Robert kicks the bucket.";
            System.out.println("INFO in Interpreter.testInterpret(): " + sent);
            input = "sumo(Kicking,kicks-2), nsubj(kicks-2, Robert-1), root(ROOT-0, kicks-2), " +
                    "sumo(Man,Robert-1), number(SINGULAR, Robert-1), tense(PRESENT, kicks-2), " +
                    "sumo(Container,bucket-4), dobj(kicks-2, bucket-4), number(SINGULAR, bucket-4), " +
                    "det(bucket-4, the-3)";
            lex = new Lexer(input);
            cnfInput = CNF.parseSimple(lex);
            inputs = new ArrayList<CNF>();
            inputs.add(cnfInput);
            System.out.println(interp.interpretCNF(inputs));
        }
        catch (Exception e) {
            e.printStackTrace();
            System.out.println(e.getMessage());
        }
    }

    /** ***************************************************************
     */
    public static void testInterpret2() {

        try {
            KBmanager.getMgr().initializeOnce();
            Interpreter interp = new Interpreter();
            interp.initialize();
            String sent = "Juan Lopez loves New York City.";
            interp.interpretSingle(sent);
        }
        catch (Exception e) {
            e.printStackTrace();
            System.out.println(e.getMessage());
        }
    }

    /** *************************************************************
     * A test method
     */
    public static void testPreserve() {

        System.out.println("INFO in Interpreter.testPreserve()--------------------");
        Interpreter interp = new Interpreter();
        String rule = "+sumo(?O,?X), nsubj(?E,?X), dobj(?E,?Y) ==> " +
                "{(foo ?E ?X)}.";
        Rule r = new Rule();
        r = Rule.parseString(rule);
        RuleSet rsin = new RuleSet();
        rsin.rules.add(r);
        interp.rs = canon(rsin);
        Clausifier.clausify(r.lhs);
        String input = "sumo(Object,bank-2), nsubj(hired-3, bank-2),  dobj(hired-3, John-4).";
        Lexer lex = new Lexer(input);
        CNF cnfInput = CNF.parseSimple(lex);
        ArrayList<CNF> inputs = new ArrayList<CNF>();
        inputs.add(cnfInput);
        interp.interpretCNF(inputs);
        System.out.println("INFO in Interpreter.testPreserve(): result should be KIF for foo and sumo");

        interp = new Interpreter();
        String rule2 = "sumo(?O,?X), nsubj(?E,?X), dobj(?E,?Y) ==> " +  // no preserve tag
                "{(foo ?E ?X)}.";
        r = new Rule();
        r = Rule.parseString(rule2);
        rsin = new RuleSet();
        rsin.rules.add(r);
        interp.rs = canon(rsin);
        Clausifier.clausify(r.lhs);
        input = "sumo(Object,bank-2), nsubj(hired-3, bank-2),  dobj(hired-3, John-4).";
        lex = new Lexer(input);
        cnfInput = CNF.parseSimple(lex);
        inputs = new ArrayList<CNF>();
        inputs.add(cnfInput);
        interp.interpretCNF(inputs);
        System.out.println("INFO in Interpreter.testPreserve(): result should be KIF for foo");

        interp = new Interpreter();
        String rule3 = "sumo(?O,?X) ==> (instance(?X,?O)).";
        r = new Rule();
        r = Rule.parseString(rule3);
        rsin = new RuleSet();
        rsin.rules.add(r);
        interp.rs = canon(rsin);
        Clausifier.clausify(r.lhs);
        input = "det(river-5,the-4), sumo(Walking,walks-2), sumo(Human,John-1), sumo(River,river-5).";
        lex = new Lexer(input);
        cnfInput = CNF.parseSimple(lex);
        inputs = new ArrayList<CNF>();
        inputs.add(cnfInput);
        interp.interpretCNF(inputs);
        System.out.println("INFO in Interpreter.testPreserve(): result should be KIF:");
        System.out.println(" (and (det river-5 the-4) (instance walks-2 Walking) (instance John-1 Human) (instance river-5 River))");
    }

    /** ***************************************************************
     */
    public static void testQuestionPreprocess() {

        String input = "advmod(is-2, Where-1), root(ROOT-0, is-2), nsubj(is-2, John-3).";
        Lexer lex = new Lexer(input);
        CNF cnfInput = CNF.parseSimple(lex);
        Rule r = new Rule();
        preProcessQuestionWords(cnfInput);
        System.out.println("INFO in Interpreter.testQuestionPreprocess(): Input: " + cnfInput);
    }

    /** ***************************************************************
     */
    public static void testPostProcess() {

        String input = "(and (agent kicks-2 John-1) (instance kicks-2 Kicking) (patient kicks-2 cart-4)" +
                "(instance John-1 Human) (instance cart-4 Wagon))";
        System.out.println("INFO in Interpreter.testPostProcess(): Input: " + postProcess(input));
    }

    /** ***************************************************************
     */
    public static void testWSD() {

        KBmanager.getMgr().initializeOnce();
        String input = "Amelia is a pilot.";
        ArrayList<String> results = null;
        try {
            results = DependencyConverter.getDependencies(input);
        }
        catch (Exception e) {
            e.printStackTrace();
            System.out.println(e.getMessage());
        }
       // List<String> wsd = findWSD(results);
        //System.out.println("INFO in Interpreter.testWSD(): Input: " + wsd);
    }

    /** ***************************************************************
     */
    public static void testGraphMatch() {

        System.out.println("INFO in Interpreter.testGraphMatch()");
        Interpreter interp = new Interpreter();
        KBmanager.getMgr().initializeOnce();
        try {
            interp.initialize();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        interp.simFlood = true;
        interp.inference = false;
        String input = "Mary walks.";
        interp.interpretSingle(input);
        input = "John walks.";
        interp.interpretSingle(input);
        input = "Who walks?";
        interp.interpretSingle(input);
    }

    /** ***************************************************************
     */
    public static void testTimeDateExtraction() throws IOException {

        System.out.println("INFO in Interpreter.testTimeDateExtraction()");
        Interpreter interp = new Interpreter();
        KBmanager.getMgr().initializeOnce();
        interp.initialize();

        System.out.println("----------------------");
        String input = "John killed Mary on 31 March and also in July 1995 by travelling back in time.";
        System.out.println(input);
        String sumoTerms = interp.interpretSingle(input);
        System.out.println(sumoTerms);

        System.out.println("----------------------");
        input = "Amelia Mary Earhart (July 24, 1897 – July 2, 1937) was an American aviator.";
        System.out.println(input);
        sumoTerms = interp.interpretSingle(input);
        System.out.println(sumoTerms);

        System.out.println("----------------------");
        input = "Earhart vanished over the South Pacific Ocean in July 1937 while trying to fly around the world.";
        System.out.println(input);
        sumoTerms = interp.interpretSingle(input);
        System.out.println(sumoTerms);

        System.out.println("----------------------");
        input = "She was declared dead on January 5, 1939.";
        System.out.println(input);
        sumoTerms = interp.interpretSingle(input);
        System.out.println(sumoTerms);

        System.out.println("----------------------");
        input = "Bob went to work only 5 times in 2003.";
        System.out.println(input);
        sumoTerms = interp.interpretSingle(input);
        System.out.println(sumoTerms);
    }

    /** ***************************************************************
     */
    public void testAddQuantification() {

        String input = "(and (agent kicks-2 John-1) (instance kicks-2 Kicking) (patient kicks-2 cart-4)" +
                "(instance John-1 Human) (instance cart-4 Wagon))";
        String s1 = postProcess(input);
        System.out.println("INFO in Interpreter.testAddQuantification(): Input: " + input);
        System.out.println("INFO in Interpreter.testAddQuantification(): Output: " + addQuantification(s1));

        input = "(and (agent kicks-2 ?WH) (instance kicks-2 Kicking) (patient kicks-2 cart-4)" +
                "(instance ?WH Human) (instance cart-4 Wagon))";
        s1 = postProcess(input);
        System.out.println("INFO in Interpreter.testAddQuantification(): Input: " + input);
        System.out.println("INFO in Interpreter.testAddQuantification(): Output: " + addQuantification(s1));
    }

    /** ***************************************************************
     */
    public static void main(String[] args) throws IOException {

        System.out.println("INFO in Interpreter.main()");
        Interpreter interp = new Interpreter();
        if (args != null && args.length > 0 && (args[0].equals("-s") || args[0].equals("-i"))) {
            KBmanager.getMgr().initializeOnce();
            interp.initialize();
        }
        if (args != null && args.length > 0 && args[0].equals("-s")) {
            interp.interpretSingle(args[1]);
        }
        else if (args != null && args.length > 0 && args[0].equals("-i")) {
            interp.interpInter();
        }
        else if (args != null && args.length > 0 && args[0].equals("-t")) {
            interp.testUnifyInter();
        }
        else if (args != null && args.length > 0 && args[0].equals("-h")) {
            System.out.println("Semantic Rewriting with SUMO, Sigma and E");
            System.out.println("  options:");
            System.out.println("  -h - show this help screen");
            System.out.println("  -s - runs one conversion of one sentence");
            System.out.println("  -i - runs a loop of conversions of one sentence at a time,");
            System.out.println("  -t - test unification of a rule with a set of facts,");
            System.out.println("       prompting the user for more.  Empty line to exit.");
            System.out.println("       'load filename' will load a specified rewriting rule set.");
            System.out.println("       'ir/autoir/noir' will determine whether TF/IDF is run always, on inference failure or never.");
            System.out.println("       'reload' (no quotes) will reload the rewriting rule set.");
            System.out.println("       'inference/noinference' will turn on/off inference.");
            System.out.println("       'debug/nodebug' will turn on/off debugging messages.");
            System.out.println("       'sim/nosim' will turn on/off similarity flooding (and toggle inference).");
            System.out.println("       'addUnprocessed/noUnprocessed' will add/not add unprocessed clauses.");
            System.out.println("       'showr/noshowr' will show/not show what rules get matched.");
            System.out.println("       'showrhs/noshowrhs' will show/not show what right hand sides get asserted.");
            System.out.println("       'quit' to quit");
            System.out.println("       Ending a sentence with a question mark will trigger a query,");
            System.out.println("       otherwise results will be asserted to the KB. Don't end commands with a period.");
        }
        else {
            //testUnify();
            testUnify8();
            //testInterpret2();
            //testInterpretGenCNF();
            //testPreserve();
            //testQuestionPreprocess();
            //testPostProcess();
            //testTimeDateExtraction();
            //testAddQuantification();
        }
    }
}