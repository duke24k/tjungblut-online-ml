package de.jungblut.online.regularization;

import de.jungblut.math.DoubleVector;
import de.jungblut.math.minimize.CostGradientTuple;

public class GradientDescentUpdater implements WeightUpdater {

  /**
   * Simplistic gradient descent without regularization.
   */
  @Override
  public CostWeightTuple computeNewWeights(DoubleVector theta,
      DoubleVector gradient, double learningRate, long iteration, double cost) {

    CostGradientTuple computedGradient = computeGradient(theta, gradient,
        learningRate, iteration, cost);

    DoubleVector dampened = computedGradient.getGradient().multiply(
        learningRate);

    return new CostWeightTuple(computedGradient.getCost(),
        theta.subtract(dampened));
  }

  @Override
  public CostGradientTuple computeGradient(DoubleVector theta,
      DoubleVector gradient, double learningRate, long iteration, double cost) {
    return new CostGradientTuple(cost, gradient);
  }

}
