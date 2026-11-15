package com.eza.spicyex.lyrics.language.mandarin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A direct Java implementation of the pinned g2pM 0.1.2.5 model (Apache-2.0).
 *
 * <p>Official architecture: embedding 64 -&gt; one-layer BiLSTM (32 hidden per direction) -&gt;
 * FC 32 -&gt; ReLU -&gt; FC 876. PyTorch gate order is [input, forget, cell, output]. The official
 * NumPy inference reverses the sentence for the backward layer, runs the reverse weight matrices,
 * and reverses the resulting hidden states back. This class does the same.
 *
 * <p>All arithmetic follows the pinned TypeScript port: dot products accumulate in double over
 * float32 operands, and every intermediate that the port rounds with {@code Math.fround} is stored
 * as a Java {@code float}. Numerical parity with the TypeScript logits is within 1e-5 absolute.
 */
public final class G2pMModel {
    public static final int INPUT_SIZE = 64;
    public static final int HIDDEN_SIZE = 32;

    /** One ranked class for one position. */
    public static final class Top {
        public final int index;
        public final float logit;
        public final double probability;

        Top(int index, float logit, double probability) {
            this.index = index;
            this.logit = logit;
            this.probability = probability;
        }
    }

    private final int vocabularySize;
    private final int classCount;
    private final float[] embedding;
    private final float[] weightIh;
    private final float[] weightHh;
    private final float[] biasIh;
    private final float[] biasHh;
    private final float[] weightIhReverse;
    private final float[] weightHhReverse;
    private final float[] biasIhReverse;
    private final float[] biasHhReverse;
    private final float[] fc0Weight;
    private final float[] fc0Bias;
    private final float[] fc1Weight;
    private final float[] fc1Bias;

    public G2pMModel(Map<String, float[]> tensors, int vocabularySize, int classCount) {
        this.vocabularySize = vocabularySize;
        this.classCount = classCount;
        this.embedding = tensor(tensors, "embedding.weight", vocabularySize, INPUT_SIZE);
        this.weightIh = tensor(tensors, "lstm.weight_ih_l0", HIDDEN_SIZE * 4, INPUT_SIZE);
        this.weightHh = tensor(tensors, "lstm.weight_hh_l0", HIDDEN_SIZE * 4, HIDDEN_SIZE);
        this.biasIh = tensor(tensors, "lstm.bias_ih_l0", HIDDEN_SIZE * 4);
        this.biasHh = tensor(tensors, "lstm.bias_hh_l0", HIDDEN_SIZE * 4);
        this.weightIhReverse = tensor(tensors, "lstm.weight_ih_l0_reverse", HIDDEN_SIZE * 4, INPUT_SIZE);
        this.weightHhReverse = tensor(tensors, "lstm.weight_hh_l0_reverse", HIDDEN_SIZE * 4, HIDDEN_SIZE);
        this.biasIhReverse = tensor(tensors, "lstm.bias_ih_l0_reverse", HIDDEN_SIZE * 4);
        this.biasHhReverse = tensor(tensors, "lstm.bias_hh_l0_reverse", HIDDEN_SIZE * 4);
        this.fc0Weight = tensor(tensors, "logit_layer.0.weight", HIDDEN_SIZE, HIDDEN_SIZE * 2);
        this.fc0Bias = tensor(tensors, "logit_layer.0.bias", HIDDEN_SIZE);
        this.fc1Weight = tensor(tensors, "logit_layer.2.weight", classCount, HIDDEN_SIZE);
        this.fc1Bias = tensor(tensors, "logit_layer.2.bias", classCount);
    }

    public int vocabularySize() {
        return vocabularySize;
    }

    public int classCount() {
        return classCount;
    }

    /** Raw logits for every model position in one forward/backward pass. */
    public float[][] logits(int[] inputIds, int[] positions) {
        validateIds(inputIds);
        float[][] result = new float[positions.length][];
        int length = inputIds.length;
        float[] forward = runLayer(inputIds, false);
        float[] backwardReversed = runLayer(reversed(inputIds), true);
        float[] combined = new float[HIDDEN_SIZE * 2];
        for (int at = 0; at < positions.length; at++) {
            int position = positions[at];
            if (position < 0 || position >= length) {
                throw new IllegalArgumentException("Invalid g2pM position " + position + ".");
            }
            System.arraycopy(forward, position * HIDDEN_SIZE, combined, 0, HIDDEN_SIZE);
            int source = (length - 1 - position) * HIDDEN_SIZE;
            System.arraycopy(backwardReversed, source, combined, HIDDEN_SIZE, HIDDEN_SIZE);
            result[at] = logits(combined);
        }
        return result;
    }

    /** Ranked classes for every requested model position in one pass. */
    public Top[][] predict(int[] inputIds, int[] positions, int topK) {
        float[][] raw = logits(inputIds, positions);
        Top[][] out = new Top[raw.length][];
        for (int at = 0; at < raw.length; at++) {
            out[at] = top(raw[at], topK);
        }
        return out;
    }

    private Top[] top(float[] rawLogits, int count) {
        float maximum = Float.NEGATIVE_INFINITY;
        for (float value : rawLogits) {
            if (value > maximum) maximum = value;
        }
        double total = 0;
        float[] exponential = new float[rawLogits.length];
        for (int index = 0; index < rawLogits.length; index++) {
            float value = (float) Math.exp(rawLogits[index] - maximum);
            exponential[index] = value;
            total += value;
        }
        List<Integer> indices = new ArrayList<>(rawLogits.length);
        for (int index = 0; index < rawLogits.length; index++) indices.add(index);
        indices.sort((left, right) -> {
            int byValue = Float.compare(rawLogits[right], rawLogits[left]);
            return byValue != 0 ? byValue : Integer.compare(left, right);
        });
        int size = Math.max(0, Math.min(count, indices.size()));
        Top[] out = new Top[size];
        for (int at = 0; at < size; at++) {
            int index = indices.get(at);
            out[at] = new Top(index, rawLogits[index], exponential[index] / total);
        }
        return out;
    }

    /** One LSTM direction. {@code reverse} selects the reverse weights, not a different traversal. */
    private float[] runLayer(int[] inputIds, boolean reverse) {
        float[] weightIh = reverse ? this.weightIhReverse : this.weightIh;
        float[] weightHh = reverse ? this.weightHhReverse : this.weightHh;
        float[] biasIh = reverse ? this.biasIhReverse : this.biasIh;
        float[] biasHh = reverse ? this.biasHhReverse : this.biasHh;
        int length = inputIds.length;
        float[] hiddenStates = new float[length * HIDDEN_SIZE];
        float[] hidden = new float[HIDDEN_SIZE];
        float[] cell = new float[HIDDEN_SIZE];
        float[] nextHidden = new float[HIDDEN_SIZE];
        float[] nextCell = new float[HIDDEN_SIZE];
        float[] input = new float[INPUT_SIZE];
        float[] gates = new float[HIDDEN_SIZE * 4];
        int gateCount = HIDDEN_SIZE * 4;

        for (int position = 0; position < length; position++) {
            int embeddingOffset = inputIds[position] * INPUT_SIZE;
            System.arraycopy(embedding, embeddingOffset, input, 0, INPUT_SIZE);
            for (int gate = 0; gate < gateCount; gate++) {
                float inputPart = (float) (biasIh[gate]
                        + dot(input, 0, weightIh, gate * INPUT_SIZE, INPUT_SIZE));
                float hiddenPart = (float) (biasHh[gate]
                        + dot(hidden, 0, weightHh, gate * HIDDEN_SIZE, HIDDEN_SIZE));
                gates[gate] = inputPart + hiddenPart;
            }
            for (int unit = 0; unit < HIDDEN_SIZE; unit++) {
                float inputGate = sigmoid(gates[unit]);
                float forgetGate = sigmoid(gates[HIDDEN_SIZE + unit]);
                float cellGate = (float) Math.tanh(gates[HIDDEN_SIZE * 2 + unit]);
                float outputGate = sigmoid(gates[HIDDEN_SIZE * 3 + unit]);
                float next = forgetGate * cell[unit] + inputGate * cellGate;
                nextCell[unit] = next;
                nextHidden[unit] = outputGate * (float) Math.tanh(next);
            }
            System.arraycopy(nextHidden, 0, hiddenStates, position * HIDDEN_SIZE, HIDDEN_SIZE);
            System.arraycopy(nextHidden, 0, hidden, 0, HIDDEN_SIZE);
            System.arraycopy(nextCell, 0, cell, 0, HIDDEN_SIZE);
        }
        return hiddenStates;
    }

    private float[] logits(float[] combined) {
        float[] hidden = new float[HIDDEN_SIZE];
        for (int unit = 0; unit < HIDDEN_SIZE; unit++) {
            float value = (float) (fc0Bias[unit]
                    + dot(combined, 0, fc0Weight, unit * HIDDEN_SIZE * 2, HIDDEN_SIZE * 2));
            hidden[unit] = value > 0 ? value : 0;
        }
        float[] output = new float[classCount];
        for (int classIndex = 0; classIndex < classCount; classIndex++) {
            output[classIndex] = (float) (fc1Bias[classIndex]
                    + dot(hidden, 0, fc1Weight, classIndex * HIDDEN_SIZE, HIDDEN_SIZE));
        }
        return output;
    }

    private static float sigmoid(float value) {
        return (float) (1.0 / (1.0 + Math.exp(-value)));
    }

    /** Double accumulation over float32 operands, matching JavaScript number arithmetic. */
    private static double dot(float[] values, int offset, float[] weights, int weightOffset, int length) {
        double sum = 0;
        for (int index = 0; index < length; index++) {
            sum += (double) values[offset + index] * weights[weightOffset + index];
        }
        return sum;
    }

    private void validateIds(int[] inputIds) {
        if (inputIds.length == 0) throw new IllegalArgumentException("g2pM input must not be empty.");
        for (int id : inputIds) {
            if (id < 0 || id >= vocabularySize) {
                throw new IllegalArgumentException("Invalid g2pM token id " + id + ".");
            }
        }
    }

    private static int[] reversed(int[] values) {
        int[] out = new int[values.length];
        for (int index = 0; index < values.length; index++) out[index] = values[values.length - 1 - index];
        return out;
    }

    private static float[] tensor(Map<String, float[]> tensors, String name, int... shape) {
        float[] found = tensors.get(name);
        if (found == null) throw new IllegalArgumentException("Missing model tensor " + name + ".");
        int expected = 1;
        for (int size : shape) expected *= size;
        if (found.length != expected) {
            throw new IllegalArgumentException("Tensor " + name + " has " + found.length
                    + " values, expected " + expected + ".");
        }
        return found;
    }
}
