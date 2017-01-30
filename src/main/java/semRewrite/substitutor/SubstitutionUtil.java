/*
 * Copyright 2014-2015 IPsoft
 *
 * Author: Andrei Holub andrei.holub@ipsoft.com
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program ; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston,
 * MA  02111-1307 USA
 */

package semRewrite.substitutor;

import semRewrite.substitutor.ClauseSubstitutor;
import semRewrite.substitutor.CoreLabelSequence;
import semRewrite.substitutor.SimpleSubstitutorStorage;
import com.google.common.collect.Lists;
import edu.stanford.nlp.ling.CoreLabel;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SubstitutionUtil {

    // predicate(word-1, word-2)
    // predicate(word, word-1)
    // predicate(word, word)
    // predicate(word-1, word)
    public static final Pattern CLAUSE_SPLITTER = Pattern.compile("([^\\(]+)\\((.+?(-\\d+)?),\\s*(.+?(-\\d+)?)\\)");
    public static final Pattern CLAUSE_PARAM = Pattern.compile("(.+)-(\\d+)");
    public static final boolean debug = false;

    /** **************************************************************
     * Takes the list of clauses and substitutes them using provided substitutor.
     * If both arguments are substituted to the same group, this entry will be
     * removed from the original input.
     */
    public static List<String> groupClauses(ClauseSubstitutor substitutor, List<String> clauses) {

        if (debug) System.out.println("INFO in SubstitutionUtil.groupClauses(): clauses: " + clauses);
        if (debug)  System.out.println("INFO in SubstitutionUtil.groupClauses(): substitutor: " + substitutor);
        Iterator<String> clauseIterator = clauses.iterator();
        List<String> modifiedClauses = Lists.newArrayList();
        while (clauseIterator.hasNext()) {
            String clause = clauseIterator.next();
            if (debug) System.out.println("INFO in SubstitutionUtil.groupClauses(): clause: " + clause);
            Matcher m = CLAUSE_SPLITTER.matcher(clause);
            if (m.matches()) {
                // FIXME LOW: Still waiting for optimization
                String attr1 = m.group(2);
                String attr2 = m.group(4);
                if (debug) System.out.println("INFO in SubstitutionUtil.groupClauses(): attr1: " + attr1);
                if (debug) System.out.println("INFO in SubstitutionUtil.groupClauses(): attr2: " + attr2);
                if ((m.group(3) != null && substitutor.containsKey(attr1.toUpperCase()))
                        || (m.group(5) != null && substitutor.containsKey(attr2.toUpperCase()))) {
                    CoreLabelSequence attr1Grouped = substitutor.getGrouped(attr1.toUpperCase());
                    CoreLabelSequence attr2Grouped = substitutor.getGrouped(attr2.toUpperCase());
                    if (debug) System.out.println("INFO in SubstitutionUtil.groupClauses(): attr1Grouped: " + attr1Grouped);
                    if (debug) System.out.println("INFO in SubstitutionUtil.groupClauses(): attr2Grouped: " + attr2Grouped);
                   // if (attr1Grouped.toString() != null && attr2Grouped != null &&
                    if (!Objects.equals(attr1Grouped, attr2Grouped)) {
                            // delete clauses like amod() and nn() that have parts of a compound noun as args
                        clauseIterator.remove();
                        String label = m.group(1);
                        String arg1 = attr1;
                        if (attr1Grouped != null)
                            arg1 = attr1Grouped.toStringWithNumToken();
                        String arg2 = attr2;
                        if (attr2Grouped != null)
                            arg2 = attr2Grouped.toStringWithNumToken();
                        String modClause = label + "(" + arg1  + "," + arg2 + ")";
                        if (debug) System.out.println("INFO in SubstitutionUtil.groupClauses(): modClause: " + modClause);
                        modifiedClauses.add(modClause);
                    }
                }
                else
                    modifiedClauses.add(clause);
            }
            else
                System.out.println("Error in SubstitutionUtil.groupClauses(): unmatched clause: " + clause);
        }

        if (debug) System.out.println("INFO in SubstitutionUtil.groupClauses(2): " + modifiedClauses);
        clauses.addAll(modifiedClauses);
        return modifiedClauses;
    }

    /** **************************************************************
     */
    public static void test() {

        CoreLabel cl1 = new CoreLabel();
        cl1.setValue("C.");
        cl1.setIndex(4);
        List<CoreLabel>  lcl = new ArrayList<>();
        List<CoreLabel>  lcl2 = new ArrayList<>();
        lcl.add(cl1);
        lcl2.add(cl1);
        CoreLabel cl2 = new CoreLabel();
        cl2.setValue("S.");
        cl2.setIndex(5);
        lcl.add(cl2);
        lcl2.add(cl2);
        CoreLabel cl3 = new CoreLabel();
        cl3.setValue("Lewis".toUpperCase());
        cl3.setIndex(6);
        lcl.add(cl3);
        cl3 = new CoreLabel();
        cl3.setValue("Lewis");
        cl3.setIndex(6);
        lcl2.add(cl3);
        CoreLabelSequence cls = new CoreLabelSequence(lcl);
        CoreLabelSequence cls2 = new CoreLabelSequence(lcl2);
        //subst =  [{[C.-4, S.-5, Lewis-6]=[C.-4, S.-5, Lewis-6]}, {}];
        String clause = "number(SINGULAR, Lewis-6)";
        List<String> clauses = new ArrayList<>();
        clauses.add(clause);
        SimpleSubstitutorStorage sss = new SimpleSubstitutorStorage();
        Map<CoreLabelSequence,CoreLabelSequence> m = new HashMap<>();
        m.put(cls,cls2);
        sss.addGroups(m);
        System.out.println("SubstitutionUtil.test(): " + groupClauses(sss,clauses));
    }

    /****************************************************************
     */
    public static void main(String[] args) {
        test();
    }
}
