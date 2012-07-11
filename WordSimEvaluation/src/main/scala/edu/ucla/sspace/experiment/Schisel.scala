/*
 * Copyright (c) 2012, Lawrence Livermore National Security, LLC. Produced at
 * the Lawrence Livermore National Laboratory. Written by Keith Stevens,
 * kstevens@cs.ucla.edu OCEC-10-073 All rights reserved. 
 *
 * This file is part of the S-Space package and is covered under the terms and
 * conditions therein.
 *
 * The S-Space package is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2 as published
 * by the Free Software Foundation and distributed hereunder to you.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND NO REPRESENTATIONS OR WARRANTIES,
 * EXPRESS OR IMPLIED ARE MADE.  BY WAY OF EXAMPLE, BUT NOT LIMITATION, WE MAKE
 * NO REPRESENTATIONS OR WARRANTIES OF MERCHANT- ABILITY OR FITNESS FOR ANY
 * PARTICULAR PURPOSE OR THAT THE USE OF THE LICENSED SOFTWARE OR DOCUMENTATION
 * WILL NOT INFRINGE ANY THIRD PARTY PATENTS, COPYRIGHTS, TRADEMARKS OR OTHER
 * RIGHTS.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

// Import the required Mallet code.
import cc.mallet.types.InstanceList
import cc.mallet.topics.ParallelTopicModel

// Import some handy code for managing matrices from the S-Space package.
import edu.ucla.sspace.basis.StringBasisMapping
import edu.ucla.sspace.matrix.ArrayMatrix
import edu.ucla.sspace.matrix.MatrixIO
import edu.ucla.sspace.matrix.MatrixIO.Format
import edu.ucla.sspace.util.SerializableUtil

// Other standard imports from scala and java libraries.
import scala.collection.JavaConversions.asJavaCollection
import scala.io.Source

import java.io.File
import java.io.PrintWriter


/**
 * A scala wrapper for using <a href="http://mallet.cs.umass.edu/">Mallet</a> on
 * on a simple corpus where each document is on a line in a multi-line file and
 * the tokens in each document can be split using white space.  After the model
 * has been learned Schisel will save the word by topic probabilities and
 * document by topic probabilities as dense matrices.  It will also store the
 * top 10 words per topic to a separate file.
 */
object Schisel {

    /**
     * Returns the number of available processors.
     */
    def allProcs = Runtime.getRuntime.availableProcessors

    /**
     * Creates and runs a {@link ParallelTopicModel} using Mallet.  The
     * following paramenters will be set automatically before processing.
     */
    def runLDA(instanceList:InstanceList,
               alpha:Double = 50.0, beta:Double = 0.01,
               showTopicInterval:Int = 50, topWordsPerInterval:Int = 10,
               numTopics:Int = 50, numIterations:Int = 500,
               optimizationInterval:Int = 25, optimizationBurnin:Int = 200,
               useSymmetricAlpha:Boolean = false, numThreads:Int = allProcs) = {
        val topicModel = new ParallelTopicModel(numTopics, alpha, beta)
        topicModel.addInstances(instanceList)
        topicModel.setTopicDisplay(showTopicInterval, topWordsPerInterval)
        topicModel.setNumIterations(numIterations)
        topicModel.setOptimizeInterval(optimizationInterval)
        topicModel.setBurninPeriod(optimizationBurnin)
        topicModel.setSymmetricAlpha(useSymmetricAlpha)
        topicModel.setNumThreads(numThreads)
        topicModel.estimate
        topicModel
    }

    /**
     * Prints the top {@code wordsPerTopic} words for each topic in the model
     * and stores the topic words, with one topic per line, in {@code outFile}.
     */
    def printTopWords(outFile:String, topicModel:ParallelTopicModel, 
                      wordsPerTopic:Int) {
        System.err.println("Printing top words")
        val w = new PrintWriter(outFile)
        topicModel.getTopWords(wordsPerTopic).foreach(
            t => w.println(t.mkString(" ")))
        w.close
    }

    /**
     * Prints the document by topic probabilities as a dense matrix with each
     * document as a row and each topic as a column.
     */
    def printDocumentSpace(outFile:String, topicModel:ParallelTopicModel,
                           numDocuments:Int, numTopics:Int) {
        System.err.println("Printing Document Space")
        val alpha = topicModel.alpha
        val writer = new PrintWriter(outFile)
        for (d <- 0 until numDocuments)
            writer.println(topicModel.getTopicProbabilities(d).zipWithIndex
                                                              .map(_.swap)
                                                              .sorted
                                                              .map(_._2)
                                                              .mkString(" "))
        writer.close
    }

    def printBasis(outFile:String, alphabet:String) {
        val writer = new PrintWriter(outFile)
        writer.print(alphabet)
        writer.close
    }

    /**
     * Prints the word by topic probabilities as a dense matrix with each
     * word as a row and each topic as a column.
     */
    def printWordSpace(outFile:String, topicModel:ParallelTopicModel,
                       numTopics:Int) {
        val beta = topicModel.beta
        val topicMask = topicModel.topicMask
        val topicBits = topicModel.topicBits

        val wordSpace = new ArrayMatrix(topicModel.numTypes, topicModel.numTopics)
        // Initialize the matrix so that every entry has the beta smoothing
        // parameter.
        for (r <- 0 until wordSpace.rows; c <- 0 until wordSpace.columns)
            wordSpace.set(r, c, beta)

        // Iterate through the real topic counts when they exist.  Mallet seems
        // to make this as impossible as possible, each entry in the matrix
        // encodes the topic id and the count for the pairing, in no particular
        // order (it's probably sorted by frequency).  The actual indexes in
        // each row of the typeTopicCount are meaningless, and many entries are
        // 0, hency why we have to preset the beta value as above, it's a pain
        // in the bum to know when we've set a wordXtopic count or not with
        // their data structure.
        for ((topicCounts, row) <- topicModel.typeTopicCounts.zipWithIndex;
             count <- topicCounts; if count > 0)
            wordSpace.add(row, count & topicMask, count >> topicBits)

        MatrixIO.writeMatrix(wordSpace, outFile, Format.DENSE_TEXT)
    }

    def main(args:Array[String]) {
        if (args.size != 5) {
            printf("usage: Schisel <contentWords.txt> <nTopics> <docs.txt> <out_name> <serializedName>\n")
            System.exit(1);
        }

        val contentWords = Source.fromFile(args(0)).getLines.toList

        // Load up the instances for LDA to process.
        val instances = InstanceUtil.buildInstanceList(
             Source.fromFile(path, "ISO-8859-1").getLines, contentWords)

        // Extract the number of desired topics and number of documents.
        val numTopics = args(1).toInt
        val numDocuments = instances.size
        System.err.println("Training model")

        // Run LDA.
        val topicModel = runLDA(instances, numTopics=numTopics)

        // Print out the top words, the word by topic probabilities, and
        // document by topic probabilities as dense matrices.
        val outBase = args(3)
        printBasis(outBase + ".basis", topicModel.alphabet.toString())
        printWordSpace(outBase+"-ws.dat", topicModel, numTopics)
        printDocumentSpace(outBase+"-ds.dat", 
                           topicModel, numDocuments, numTopics)
        topicModel.write(new File(args(4)))
        System.err.println("Done")
    }
}
