package com.articulate.nlp.pipeline;

/*
Copyright 2014-2015 IPsoft

Author: Andrei Holub andrei.holub@ipsoft.com

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

import com.articulate.sigma.StringUtil;
import com.articulate.sigma.KBmanager;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.time.TimeAnnotations;
import edu.stanford.nlp.time.TimeAnnotator;
import edu.stanford.nlp.util.CoreMap;
import com.articulate.nlp.WNMultiWordAnnotator;
import com.articulate.nlp.WSDAnnotator;
import com.articulate.nlp.TimeSUMOAnnotator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;

public class Pipeline {

    public StanfordCoreNLP pipeline;
    public static final String defaultProp = "tokenize, ssplit, pos, lemma, " +
        "ner, nersumo, gender, parse, depparse, dcoref, entitymentions, wnmw, wsd, tsumo";

    /** ***************************************************************
     */
    public Pipeline() {

        this(false);
    }

    /** ***************************************************************
     */
    public Pipeline(boolean useDefaultPCFGModel) {

        this(useDefaultPCFGModel,defaultProp);
    }

    /** ***************************************************************
     */
    public Pipeline(boolean useDefaultPCFGModel, String propString) {

        System.out.println("Pipeline(): initializing with " + propString);
        Properties props = new Properties();
        // props.setProperty("annotators", "tokenize, ssplit, pos, lemma, ner, parse, depparse, dcoref, entitymentions");
        props.put("annotators", propString);

        //props.setProperty("parse.kbest", "2");
        //props.setProperty("depparse.language","English");
        if (propString.contains("wsd")) {
            props.put("customAnnotatorClass.wsd", "com.articulate.nlp.WSDAnnotator");
            props.put("customAnnotatorClass.wnmw", "com.articulate.nlp.WNMultiWordAnnotator");
        }
        if (propString.contains("tsumo")) {
            props.put("customAnnotatorClass.tsumo", "com.articulate.nlp.TimeSUMOAnnotator");
        }
        if (propString.contains("nersumo")) {
            props.put("customAnnotatorClass.nersumo", "com.articulate.nlp.NERAnnotator");
        }
        //if (propString.contains("ner"))
        //    props.put("ner.model", "edu/stanford/nlp/models/ner/english.muc.7class.distsim.crf.ser.gz");
        //props.put("depparse.model", "edu/stanford/com.articulate.nlp/models/parser/nndep/english_SD.gz");

        if (propString.contains("pos,") && !useDefaultPCFGModel &&
                !Strings.isNullOrEmpty(KBmanager.getMgr().getPref("englishPCFG"))) {
            props.put("parse.model", KBmanager.getMgr().getPref("englishPCFG"));
            props.put("parser.model", KBmanager.getMgr().getPref("englishPCFG"));
            props.put("parse.flags", "");
        }
        // creates a StanfordCoreNLP object, with POS tagging, lemmatization, NER, parsing, and coreference resolution
        pipeline = new StanfordCoreNLP(props);
        if (propString.contains("tsumo")) {
            Properties prps = new Properties();
            pipeline.addAnnotator(new TimeAnnotator("sutime", prps));
            pipeline.addAnnotator(new TimeSUMOAnnotator("tsumo", prps));
        }
    }

    /** ***************************************************************
     */
    public Annotation annotate(String text) {
        
        // create an empty Annotation just with the given text
        Annotation document = new Annotation(text);
        // run all Annotators on this text
        pipeline.annotate(document);
        return document;
    }

    /** ***************************************************************
     */
    public static Annotation toAnnotation(String input) {

        Pipeline pipeline = new Pipeline(true);
        return pipeline.annotate(input);
    }

    /** ***************************************************************
     */
    public static Annotation toAnnotation(List<String> inputs) {

        return toAnnotation(String.join(" ", inputs));
    }

    /** ***************************************************************
     */
    public List<String> toDependencies(String input) {

        Annotation wholeDocument = annotate(input);
        CoreMap lastSentence = SentenceUtil.getLastSentence(wholeDocument);
        List<String> dependencies = SentenceUtil.toDependenciesList(ImmutableList.of(lastSentence));
        return dependencies;
    }

    /** ***************************************************************
     */
    public static String showResults(Annotation anno) {

        StringBuffer sb = new StringBuffer();
        if (!anno.containsKey(CoreAnnotations.SentencesAnnotation.class))
            throw new RuntimeException("Unable to find sentences in " + anno + "\n");

        sb.append("Pipeline.showResults(): time annotations at root" + "\n");
        List<CoreMap> timexAnnsAll = anno.get(TimeAnnotations.TimexAnnotations.class);
        if (timexAnnsAll != null) {
            for (CoreMap token : timexAnnsAll) {
                sb.append("time token: " + token + "\n");
                String tsumo = token.get(TimeSUMOAnnotator.TimeSUMOAnnotation.class);
                sb.append("SUMO: " + tsumo + "\n");
            }
        }

        sb.append("Pipeline.showResults(): annotations at sentence level" + "\n");
        List<CoreMap> sentences = anno.get(CoreAnnotations.SentencesAnnotation.class);
        for (CoreMap sentence : sentences) {
            List<CoreLabel> tokens = sentence.get(CoreAnnotations.TokensAnnotation.class);
            List<CoreMap> timexAnnsAllLocal = sentence.get(TimeAnnotations.TimexAnnotations.class);
            sb.append("local time: " + timexAnnsAllLocal + "\n");
            for (CoreLabel token : tokens) {
                String orig = token.originalText();
                String lemma = token.lemma();
                String pos = token.tag();
                String sense = token.get(WSDAnnotator.WSDAnnotation.class);
                String sumo = token.get(WSDAnnotator.SUMOAnnotation.class);
                String multi = token.get(WNMultiWordAnnotator.WNMultiWordAnnotation.class);
                sb.append(orig);
                if (!StringUtil.emptyString(lemma))
                    sb.append("/" + lemma);
                if (!StringUtil.emptyString(pos))
                    sb.append("/" + pos);
                if (!StringUtil.emptyString(sense))
                    sb.append("/" + sense);
                if (!StringUtil.emptyString(sumo))
                    sb.append("/" + sumo);
                if (!StringUtil.emptyString(multi))
                    sb.append("/" + multi);
                sb.append(" ");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** ***************************************************************
     */
    public static void processFile(String filename) {

        KBmanager.getMgr().initializeOnce();
        //String propString = "tokenize, ssplit, pos, lemma, ner, wsd, wnmw, tsumo";
        Pipeline p = new Pipeline(true,defaultProp);
        String contents = "";
        try {
            contents = new String(Files.readAllBytes(Paths.get(filename)));
        }
        catch (IOException ioe) {
            System.out.println("error in Pipeline.processFile()");
            ioe.printStackTrace();
        }
        Annotation wholeDocument = p.annotate(contents);
        showResults(wholeDocument);
        //List<String> sents = SentenceUtil.restoreSentences(wholeDocument);
        //ArrayList<String> SUMOs = new ArrayList<>();
        //for (String sent : sents) {
            //System.out.println(sent + WSD.collectSUMOFromWords(sent));
            //System.out.println(WSD.collectWordSenses(sent));
            //System.out.println();
        //}
    }

    /** ***************************************************************
     * Process one input through the pipeline and display the results.
     */
    public static void processOneSent(String sent) {

        KBmanager.getMgr().initializeOnce();
        Pipeline p = new Pipeline(true);
        Annotation wholeDocument = p.annotate(sent);
        showResults(wholeDocument);
    }

    /** ***************************************************************
     * Process one input at a time through the pipeline and display
     * the results
     */
    public static void interactive() {

        KBmanager.getMgr().initializeOnce();
        Pipeline p = new Pipeline(true);
        //Properties props = new Properties();
        //p.pipeline.addAnnotator(new TimeAnnotator("sutime", props));
        BufferedReader d = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("type 'quit' (without the quotes) on its own line to quit");
        String line = "";
        try {
            while (!line.equals("quit")) {
                System.out.print("> ");
                line = d.readLine();
                if (!line.equals("quit")) {
                    Annotation wholeDocument = new Annotation(line);
                    wholeDocument.set(CoreAnnotations.DocDateAnnotation.class, "2017-05-08");
                    p.pipeline.annotate(wholeDocument);
                    System.out.println(showResults(wholeDocument));
                }
            }
        }
        catch (IOException ioe) {
            ioe.printStackTrace();
            System.out.println("error in Pipeline.interactive()");
        }
    }

    /** ***************************************************************
     */
    public static void printHelp() {

        System.out.println("-h             print this help screen");
        System.out.println("-f <file>      process a file");
        System.out.println("-p \"<Sent>\"  process one quoted sentence ");
        System.out.println("-i             interactive mode ");
    }

    /** ***************************************************************
     */
    public static void main(String[] args) {

        if (args.length == 0 || args[0].equals("-h"))
            printHelp();
        else if (args[0].equals("-f") && args.length == 2)
            processFile(args[1]);
        else if (args[0].equals("-p") && args.length == 2)
            processOneSent(args[1]);
        else if (args[0].equals("-i"))
            interactive();
        else {
            printHelp();
            Annotation a = Pipeline.toAnnotation("John killed Mary on 31 March and also in July.");
            SentenceUtil.printSentences(a);
            CoreMap lastSentence = SentenceUtil.getLastSentence(a);
            List<String> dependenciesList = SentenceUtil.toDependenciesList(ImmutableList.of(lastSentence));
            System.out.println("Interpreter.interpretGenCNF(): dependencies: " + dependenciesList);
        }
    }
}