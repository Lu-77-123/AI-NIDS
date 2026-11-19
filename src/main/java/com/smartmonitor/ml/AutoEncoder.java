package com.smartmonitor.ml;

import java.util.Random;

/**
 * 轻量自编码器（纯 Java 实现，无第三方依赖）。
 *
 * 结构对称：输入层 → 若干隐层 → 瓶颈层 → 对称隐层 → 输出层，
 * 隐层激活 ReLU，输出层 Sigmoid（要求输入已归一化到 [0,1]）。
 *
 * 训练目标是最小化输入与重构输出之间的均方误差。由于只用正常流量训练，
 * 遇到异常样本时重构误差会显著变大，据此作为无监督异常检测信号。
 *
 * 实现上采用 mini-batch 随机梯度下降 + 动量，权重初始化使用 Xavier 方案。
 */
public class AutoEncoder {

    private final int[] sizes;
    private final int layerCount;
    /** 权重：weights[l][out][in] */
    private final double[][][] weights;
    /** 动量累积 */
    private final double[][][] velocity;
    private final double[][] biases;
    private final double[][] biasVelocity;
    private final Random random = new Random(20261119L);

    public AutoEncoder(int[] sizes) {
        this.sizes = sizes.clone();
        this.layerCount = sizes.length - 1;
        this.weights = new double[layerCount][][];
        this.velocity = new double[layerCount][][];
        this.biases = new double[layerCount][];
        this.biasVelocity = new double[layerCount][];

        for (int l = 0; l < layerCount; l++) {
            int in = sizes[l];
            int out = sizes[l + 1];
            weights[l] = new double[out][in];
            velocity[l] = new double[out][in];
            biases[l] = new double[out];
            biasVelocity[l] = new double[out];
            // Xavier 初始化，避免深层网络梯度消失/爆炸
            double limit = Math.sqrt(6.0 / (in + out));
            for (int o = 0; o < out; o++) {
                for (int i = 0; i < in; i++) {
                    weights[l][o][i] = (random.nextDouble() * 2 - 1) * limit;
                }
            }
        }
    }

    public int inputSize() {
        return sizes[0];
    }

    /**
     * 训练模型。
     *
     * @param data  归一化后的样本（每行长度等于输入维度）
     * @param epochs 训练轮数
     * @param lr     学习率
     * @param batch  批大小
     * @return 最后一轮的平均重构均方误差
     */
    public double train(double[][] data, int epochs, double lr, int batch) {
        if (data == null || data.length == 0) return -1;
        int n = data.length;
        int[] order = new int[n];
        for (int i = 0; i < n; i++) order[i] = i;

        double epochLoss = 0;
        final double momentum = 0.9;

        for (int e = 0; e < epochs; e++) {
            // 每轮打乱样本顺序
            for (int i = n - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                int tmp = order[i];
                order[i] = order[j];
                order[j] = tmp;
            }

            double lossSum = 0;
            for (int start = 0; start < n; start += batch) {
                int end = Math.min(start + batch, n);
                int curBatch = end - start;

                double[][][] gw = new double[layerCount][][];
                double[][] gb = new double[layerCount][];
                for (int l = 0; l < layerCount; l++) {
                    gw[l] = new double[sizes[l + 1]][sizes[l]];
                    gb[l] = new double[sizes[l + 1]];
                }

                for (int k = start; k < end; k++) {
                    lossSum += accumulateGradient(data[order[k]], gw, gb);
                }

                for (int l = 0; l < layerCount; l++) {
                    for (int o = 0; o < sizes[l + 1]; o++) {
                        for (int i = 0; i < sizes[l]; i++) {
                            double g = gw[l][o][i] / curBatch;
                            velocity[l][o][i] = momentum * velocity[l][o][i] - lr * g;
                            weights[l][o][i] += velocity[l][o][i];
                        }
                        double gbAvg = gb[l][o] / curBatch;
                        biasVelocity[l][o] = momentum * biasVelocity[l][o] - lr * gbAvg;
                        biases[l][o] += biasVelocity[l][o];
                    }
                }
            }
            epochLoss = lossSum / n;
        }
        return epochLoss;
    }

    /** 单样本前向 + 反向，梯度累加到 gw/gb，返回该样本的重构均方误差 */
    private double accumulateGradient(double[] x, double[][][] gw, double[][] gb) {
        double[][] a = new double[layerCount + 1][];
        double[][] z = new double[layerCount][];
        a[0] = x;

        for (int l = 0; l < layerCount; l++) {
            z[l] = new double[sizes[l + 1]];
            a[l + 1] = new double[sizes[l + 1]];
            boolean outputLayer = (l == layerCount - 1);
            for (int o = 0; o < sizes[l + 1]; o++) {
                double sum = biases[l][o];
                double[] row = weights[l][o];
                for (int i = 0; i < sizes[l]; i++) {
                    sum += row[i] * a[l][i];
                }
                z[l][o] = sum;
                a[l + 1][o] = outputLayer ? sigmoid(sum) : relu(sum);
            }
        }

        // 输出层误差：MSE 对输出的导数 2(ŷ-x)/n，n=维度
        double[] delta = new double[sizes[layerCount]];
        double loss = 0;
        int outDim = sizes[layerCount];
        for (int o = 0; o < outDim; o++) {
            double diff = a[layerCount][o] - x[o];
            loss += diff * diff;
            delta[o] = (2.0 / outDim) * diff * sigmoidDerivative(a[layerCount][o]);
        }

        for (int l = layerCount - 1; l >= 0; l--) {
            // 累加当前层梯度
            for (int o = 0; o < sizes[l + 1]; o++) {
                double d = delta[o];
                if (d == 0) continue;
                double[] row = gw[l][o];
                for (int i = 0; i < sizes[l]; i++) {
                    row[i] += d * a[l][i];
                }
                gb[l][o] += d;
            }
            if (l > 0) {
                double[] prevDelta = new double[sizes[l]];
                for (int i = 0; i < sizes[l]; i++) {
                    double sum = 0;
                    for (int o = 0; o < sizes[l + 1]; o++) {
                        sum += weights[l][o][i] * delta[o];
                    }
                    // ReLU 导数：z>0 时为 1
                    prevDelta[i] = z[l - 1][i] > 0 ? sum : 0;
                }
                delta = prevDelta;
            }
        }
        return loss / outDim;
    }

    /** 前向推理，返回输出向量 */
    public double[] forward(double[] x) {
        double[] current = x;
        for (int l = 0; l < layerCount; l++) {
            boolean outputLayer = (l == layerCount - 1);
            double[] next = new double[sizes[l + 1]];
            for (int o = 0; o < sizes[l + 1]; o++) {
                double sum = biases[l][o];
                double[] row = weights[l][o];
                for (int i = 0; i < sizes[l]; i++) {
                    sum += row[i] * current[i];
                }
                next[o] = outputLayer ? sigmoid(sum) : relu(sum);
            }
            current = next;
        }
        return current;
    }

    /** 单样本重构均方误差 */
    public double reconstructionError(double[] x) {
        double[] y = forward(x);
        double sum = 0;
        for (int i = 0; i < x.length; i++) {
            double d = y[i] - x[i];
            sum += d * d;
        }
        return sum / x.length;
    }

    private static double relu(double v) {
        return v > 0 ? v : 0;
    }

    private static double sigmoid(double v) {
        return 1.0 / (1.0 + Math.exp(-v));
    }

    private static double sigmoidDerivative(double activated) {
        return activated * (1.0 - activated);
    }
}
