package de.jungblut.online.minimizer;

import java.util.Deque;
import java.util.LinkedList;
import java.util.Random;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.google.common.base.Preconditions;

import de.jungblut.math.DoubleVector;
import de.jungblut.math.minimize.CostGradientTuple;
import de.jungblut.online.ml.FeatureOutcomePair;
import de.jungblut.online.regularization.CostWeightTuple;
import de.jungblut.online.regularization.GradientDescentUpdater;
import de.jungblut.online.regularization.WeightUpdater;

/**
 * Stochastic gradient descent. This class is designed to work on a parallel
 * stream and do stochastic updates to a parameter set.
 * 
 * @author thomas.jungblut
 *
 */
public class StochasticGradientDescent implements StochasticMinimizer {

  private static final Log LOG = LogFactory
      .getLog(StochasticGradientDescent.class);

  public static class StochasticGradientDescentBuilder {

    private final double alpha;
    private double breakDifference;
    private double momentum;
    private double lambda;
    private int historySize = 10;
    private int progressReportInterval = 1;
    private double holdoutValidationPercentage = 0d;
    private WeightUpdater weightUpdater = new GradientDescentUpdater();

    private StochasticGradientDescentBuilder(double alpha) {
      this.alpha = alpha;
    }

    public StochasticGradientDescent build() {
      return new StochasticGradientDescent(this);
    }

    /**
     * Add momentum to this gradient descent minimizer.
     * 
     * @param momentum the momentum to use. Between 0 and 1.
     * @return the builder again.
     */
    public StochasticGradientDescentBuilder momentum(double momentum) {
      Preconditions.checkArgument(momentum >= 0d && momentum <= 1d,
          "Momentum must be between 0 and 1.");
      this.momentum = momentum;
      return this;
    }

    /**
     * Holdout validation percentage, this will take a subset of the data on the
     * stream and do a validation on it.
     * 
     * @param perc the percentage to use. Between 0 and 1.
     * @return the builder again.
     */
    public StochasticGradientDescentBuilder holdoutValidationPercentage(
        double perc) {
      Preconditions.checkArgument(momentum >= 0d && momentum <= 1d,
          "HoldOut Percentage must be between 0 and 1.");
      this.holdoutValidationPercentage = perc;
      return this;
    }

    /**
     * Sets the weight updater, for example to use regularization. The default
     * is the normal gradient descent.
     * 
     * To set the regularization parameter use the {@link #lambda(double)}
     * method.
     * 
     * @param weightUpdater the updater to use.
     * @return the builder again.
     */
    public StochasticGradientDescentBuilder weightUpdater(
        WeightUpdater weightUpdater) {
      this.weightUpdater = Preconditions.checkNotNull(weightUpdater);
      return this;
    }

    /**
     * Sets the regularization parameter "lambda".
     * 
     * @param lambda the amount to regularize with.
     * @return the builder again.
     */
    public StochasticGradientDescentBuilder lambda(double lambda) {
      this.lambda = lambda;
      return this;
    }

    /**
     * Sets the size of the history to keep to compute average improvements and
     * output progress information.
     * 
     * @return the builder again.
     */
    public StochasticGradientDescentBuilder historySize(int historySize) {
      Preconditions.checkArgument(historySize > 0, "HistorySize must be > 0");
      this.historySize = historySize;
      return this;
    }

    /**
     * Sets the progress report interval. Since writing to the console/log might
     * be expensive, this is an easy way to limit the logging if needed.
     * 
     * @param interval the interval. E.g. every 10th iteration.
     * @return the builder again.
     */
    public StochasticGradientDescentBuilder progressReportInterval(int interval) {
      Preconditions.checkArgument(interval > 0, "ReportInterval must be > 0");
      this.progressReportInterval = interval;
      return this;
    }

    /**
     * Breaks minimization process when the given delta in costs have been
     * archieved. Usually a quite low value of 1e-4 to 1e-8.
     * 
     * @param delta the delta to break in difference between two costs.
     * @return the builder again.
     */
    public StochasticGradientDescentBuilder breakOnDifference(double delta) {
      this.breakDifference = delta;
      return this;
    }

    /**
     * Creates a new builder.
     * 
     * @param alpha the learning rate to set.
     * @return a new builder.
     */
    public static StochasticGradientDescentBuilder create(double alpha) {
      return new StochasticGradientDescentBuilder(alpha);
    }

  }

  private final Random rand = new Random();

  private final double breakDifference;
  private final double momentum;
  private final double initialAlpha;
  private final double lambda;
  private final double validationPercentage;
  private final int historySize;
  private final int progressReportInterval;
  private final WeightUpdater weightUpdater;
  private final StampedLock lock = new StampedLock();
  private final Deque<Double> history = new LinkedList<>();

  private DoubleVector lastTheta = null;
  private DoubleVector theta;
  private double alpha;
  private int validationItems;
  private double validationError;
  private boolean stopAfterThisPass = false;
  private long iteration = 0;
  private long allIterations = 0;

  private StochasticGradientDescent(StochasticGradientDescentBuilder builder) {
    this.initialAlpha = builder.alpha;
    this.alpha = this.initialAlpha;
    this.breakDifference = builder.breakDifference;
    this.momentum = builder.momentum;
    this.historySize = builder.historySize;
    this.progressReportInterval = builder.progressReportInterval;
    this.weightUpdater = builder.weightUpdater;
    this.lambda = builder.lambda;
    this.validationPercentage = builder.holdoutValidationPercentage;
  }

  @Override
  public DoubleVector minimize(DoubleVector start,
      Supplier<Stream<FeatureOutcomePair>> streamSupplier,
      StochasticCostFunction costFunction, int numPasses, boolean verbose) {
    theta = start;
    for (int pass = 0; pass < numPasses; pass++) {

      iteration = 0;
      validationError = 0;
      validationItems = 0;

      Stream<FeatureOutcomePair> currentStream = streamSupplier.get();
      final int passFinal = pass;
      currentStream.forEach((next) -> doStep(passFinal, next, costFunction,
          verbose));

      if (verbose) {
        LOG.info("Pass " + pass + " | Iteration " + iteration
            + " | Validation Cost: " + validationError
            / Math.max(validationItems, 1));
      }

      if (stopAfterThisPass) {
        break;
      }
    }

    return theta;
  }

  private void doStep(int pass, FeatureOutcomePair next,
      StochasticCostFunction costFunction, boolean verbose) {

    CostGradientTuple observed = null;
    Lock readLock = lock.asReadLock();
    try {
      readLock.lock();
      observed = costFunction.observeExample(next, theta);
      if (verbose) {
        double avgImprovement = getAverageImprovement(history);
        if (iteration % progressReportInterval == 0) {
          LOG.info("Pass " + pass + " | Iteration " + iteration
              + " | Validation Cost: " + validationError
              / Math.max(validationItems, 1) + " | Last Cost: "
              + observed.getCost() + " | Avg Improvement: " + avgImprovement);
        }
      }
    } finally {
      readLock.unlock();
    }

    // TODO this write lock is huge, can it be broken down more?

    // do the updates
    Lock asWriteLock = lock.asWriteLock();
    try {
      asWriteLock.lock();
      dropOldValues(history);

      if (validationPercentage > 0 && rand.nextDouble() < validationPercentage) {
        validationError += observed.getCost();
        validationItems++;
        // update the history
        history.addLast(validationError / Math.max(validationItems, 1));
        return; // return to not update the parameters
      }

      CostWeightTuple update = updateWeights(observed);

      // save our last parameter
      lastTheta = theta;
      theta = update.getWeight();

      computeMomentum();

      // break if we converged below the limit
      if (converged(history, breakDifference)) {
        stopAfterThisPass = true;
      }

      allIterations++;
      iteration++;

      alpha = 1d / (initialAlpha * (allIterations + 2));

    } finally {
      asWriteLock.unlock();
    }
  }

  public void computeMomentum() {
    // compute momentum
    if (lastTheta != null && momentum != 0d) {
      // we add momentum as the parameter "m" multiplied by the
      // difference of both theta vectors
      theta = theta.add((lastTheta.subtract(theta)).multiply(momentum));
    }
  }

  public CostWeightTuple updateWeights(CostGradientTuple observed) {
    // compute the final weight update
    CostWeightTuple update = weightUpdater.computeNewWeights(theta,
        observed.getGradient(), alpha, allIterations, lambda,
        observed.getCost());
    return update;
  }

  // TODO this should use a cyclic buffer instead of a deque
  private void dropOldValues(Deque<Double> lastCosts) {
    while (lastCosts.size() > historySize) {
      lastCosts.pop();
    }
  }

  private boolean converged(Deque<Double> lastCosts, double limit) {
    return Math.abs(getAverageImprovement(lastCosts)) < limit;
  }

  private double getAverageImprovement(Deque<Double> lastCosts) {
    if (lastCosts.size() >= 2) {
      double first = lastCosts.peek();
      double value = lastCosts.peekLast();
      return (value - first) / lastCosts.size();
    }
    return 0d;
  }

}
