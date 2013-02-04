/*
  Copyright 2006 by Sean Luke
  Licensed under the Academic Free License version 3.0
  See the file "LICENSE" for more information
*/


package ec.simple;
import ec.*;
import ec.util.*;

/* 
 * SimpleEvaluator.java
 * 
 * Created: Wed Aug 18 21:31:18 1999
 * By: Sean Luke
 */

/**
 * The SimpleEvaluator is a simple, non-coevolved generational evaluator which
 * evaluates every single member of every subpopulation individually in its
 * own problem space.  One Problem instance is cloned from p_problem for
 * each evaluating thread.  The Problem must implement SimpleProblemForm.
 *
 * @author Sean Luke
 * @version 1.0 
 */

public class SimpleEvaluator extends Evaluator
    {
    public static final String P_CLONE_PROBLEM = "clone-problem";
    public boolean cloneProblem;

    // checks to make sure that the Problem implements SimpleProblemForm
    public void setup(final EvolutionState state, final Parameter base)
        {
        super.setup(state,base);
        if (!(p_problem instanceof SimpleProblemForm))
            state.output.fatal("" + this.getClass() + " used, but the Problem is not of SimpleProblemForm",
                base.push(P_PROBLEM));

        cloneProblem =state.parameters.getBoolean(base.push(P_CLONE_PROBLEM), null, true);
        if (!cloneProblem && (state.breedthreads > 1)) // uh oh, this can't be right
            state.output.fatal("The Evaluator is not cloning its Problem, but you have more than one thread.", base.push(P_CLONE_PROBLEM));
        }
    
    /** A simple evaluator that doesn't do any coevolutionary
        evaluation.  Basically it applies evaluation pipelines,
        one per thread, to various subchunks of a new population. */
    public void evaluatePopulation(final EvolutionState state)
        {
        if (state.evalthreads==1)
            {
            // a minor bit of optimization
            int numinds[] = new int[state.population.subpops.length];
            int from[] = new int[state.population.subpops.length];
            for(int i = 0; i < state.population.subpops.length; i++)
                { numinds[i] = state.population.subpops[i].individuals.length; from[i] = 0; }
            if (cloneProblem)
                evalPopChunk(state,numinds,from,0,(SimpleProblemForm)(p_problem.clone()));
            else
                evalPopChunk(state,numinds,from,0,(SimpleProblemForm)(p_problem));
            }
        else
            {
            // how many threads do we really need?  No more than the maximum number of individuals in any subpopulation
            int numThreads = 0;
            for(int x = 0; x < state.population.subpops.length; x++)
                numThreads = Math.max(numThreads, state.population.subpops[x].individuals.length);
            numThreads = Math.min(numThreads, state.evalthreads);
            if (numThreads < state.breedthreads)
                state.output.warnOnce("Largest subpopulation size (" + numThreads +") is smaller than number of evalthreads (" + state.breedthreads +
                    "), so fewer evalthreads will be created.");
            
            int numinds[][] = 
                new int[numThreads][state.population.subpops.length];
            int from[][] = 
                new int[numThreads][state.population.subpops.length];
        
            for(int x=0;x<state.population.subpops.length;x++)
                {
                // we will have some extra individuals.  We distribute these among the early subpopulations
                int individualsPerThread = state.population.subpops[x].individuals.length / numThreads;  // integer division
                int slop = state.population.subpops[x].individuals.length - numThreads * individualsPerThread;
                int currentFrom = 0;
                                
                for(int y=0;y<numThreads;y++)
                    {
                    if (slop > 0)
                        {
                        numinds[y][x] = individualsPerThread + 1;
                        slop--;
                        }
                    else
                        numinds[y][x] = individualsPerThread;
                    
                    if (numinds[y][x] == 0)
                        {
                        state.output.warnOnce("More threads exist than can be used to evaluate some subpopulations (first example: subpopulation " + x + ")");
                        }

                    from[y][x] = currentFrom;
                    currentFrom += numinds[y][x];
                    }
                }

            Thread[] t = new Thread[numThreads];
            
            // start up the threads
            for(int y=0;y<numThreads;y++)
                {
                SimpleEvaluatorThread r = new SimpleEvaluatorThread();
                r.threadnum = y;
                r.numinds = numinds[y];
                r.from = from[y];
                r.me = this;
                r.state = state;
                r.p = (SimpleProblemForm)(p_problem.clone()); // should ignore cloneProblem parameter here 
                t[y] = new Thread(r);
                t[y].start();
                }

            // gather the threads
            for(int y=0;y<numThreads;y++) 
                try 
                    { 
                    t[y].join(); 
                    }
                catch(InterruptedException e)
                    {
                    state.output.fatal("Whoa! The main evaluation thread got interrupted!  Dying...");
                    }

            }
        }

    /** A private helper function for evaluatePopulation which evaluates a chunk
        of individuals in a subpopulation for a given thread.
        Although this method is declared
        public (for the benefit of a private helper class in this file),
        you should not call it. */

    protected void evalPopChunk(EvolutionState state, int[] numinds, int[] from,
        int threadnum, SimpleProblemForm p)
        {
        ((ec.Problem)p).prepareToEvaluate(state,threadnum);
        
        Subpopulation[] subpops = state.population.subpops;
        int len = subpops.length;
        
        for(int pop=0;pop<len;pop++)
            {
            // start evaluatin'!
            int fp = from[pop];
            int upperbound = fp+numinds[pop];
            Individual[] inds = subpops[pop].individuals;
            for (int x=fp;x<upperbound;x++)
                p.evaluate(state,inds[x], pop, threadnum);
            }
                        
        ((ec.Problem)p).finishEvaluating(state,threadnum);
        }
    
    /** The SimpleEvaluator determines that a run is complete by asking
        each individual in each population if he's optimal; if he 
        finds an individual somewhere that's optimal,
        he signals that the run is complete. */
    public boolean runComplete(final EvolutionState state)
        {
        for(int x = 0;x<state.population.subpops.length;x++)
            for(int y=0;y<state.population.subpops[x].individuals.length;y++)
                if (state.population.subpops[x].
                    individuals[y].fitness.isIdealFitness())
                    return true;
        return false;
        }
    }

/** A private helper class for implementing multithreaded evaluation */
class SimpleEvaluatorThread implements Runnable
    {
    public int[] numinds;
    public int[] from;
    public SimpleEvaluator me;
    public EvolutionState state;
    public int threadnum;
    public SimpleProblemForm p;
    public synchronized void run() 
        { me.evalPopChunk(state,numinds,from,threadnum,p); }
    }
