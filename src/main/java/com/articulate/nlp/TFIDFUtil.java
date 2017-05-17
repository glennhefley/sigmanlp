package com.articulate.nlp;

import com.google.common.collect.Lists;
import com.google.common.io.Resources;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.util.List;

/**
 * This code is copyright CloudMinds 2017.
 * This software is released under the GNU Public License <http://www.gnu.org/copyleft/gpl.html>.
 * Users of this code also consent, by use of this code, to credit Articulate Software
 * and Teknowledge in any writings, briefings, publications, presentations, or
 * other representations of any software which incorporates, builds on, or uses this
 * code.  Please cite the following article in any publication with references:
 * Pease, A., (2003). The Sigma Ontology Development Environment,
 * in Working Notes of the IJCAI-2003 Workshop on Ontology and Distributed Systems,
 * August 9, Acapulco, Mexico.
 *
 * Created by areed on 3/4/15.
 * Utility class for TFIDF.java
 */
public class TFIDFUtil {

    /** ***************************************************************
     * @param filename file to be read
     * @param separateSentences should sentences be separated if they occur on one line
     * @return list of strings from each line of the document
     * This method reads in a text file, breaking it into single line documents
     * Currently, sentences are not separated if they occur on the same line.
     */
    public static List<String> readFile(String filename, boolean separateSentences) throws IOException {
        
        List<String> documents = Lists.newArrayList();
        URL fileURL = Resources.getResource(filename);
        File f = new File(filename);
        BufferedReader bf = new BufferedReader(new FileReader(fileURL.getPath()));
        String line;
        while ((line = bf.readLine()) != null) {
            if (line == null || line.equals("")) 
                continue;
            documents.add(line);
        }
        return documents;
    }
}
