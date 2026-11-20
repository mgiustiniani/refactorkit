int seed = 9;
static int converted(int a, int b) {
    double ratio = a / b;
    return ratio == 2.0;
}
static int nested(int a, int b) {
    int total = (a + b) * 2;
    return total;
}
static int effects(int a, int b) {
    int total = a++ + b;
    return total;
}
static int qualified(int a, int b) {
    volatile int value = a;
    int total = value + b;
    return total;
}
static int outside(int a, int b) {
    int total = seed + b;
    return total;
}
static int conditional(int a, int b) {
    int total = a ? (a + b) : b;
    return total;
}
static int branching(int a, int b) {
    int total = a + b;
    goto done;
done:
    return total;
}
static int constantTarget(int a, int b) {
    const double ratio = a / b;
    return ratio == 2.0;
}
static int readonly(int a, int b) {
    const int base = a;
    int total = base + b;
    return total;
}
int main(void) { return converted(5, 2) == 1 && nested(7, 14) == 42 ? 0 : 1; }
