int seed = 7;
static int calculate(int left, int right);
static int computeTotal(int a, int b) {
    int sum = a + b;
    int doubled = sum * 2;
    return doubled;
}

static int calculate(int a, int b) {
    int doubled = computeTotal(a, b);
    return doubled;
}
static int escaped(int a, int b) {
    int sum = a + b;
    int doubled = sum * 2;
    return sum + doubled;
}
static int effects(int a, int b) {
    int sum = a + b;
    int doubled = sum++;
    return doubled;
}
static int qualified(int a, int b) {
    volatile int sum = a + b;
    int doubled = sum * 2;
    return doubled;
}
static int outside(int a, int b) {
    int sum = a + b;
    int doubled = sum + seed;
    return doubled;
}
static int branching(int a, int b) {
    if (a) goto finish;
    int sum = a + b;
    int doubled = sum * 2;
finish:
    return doubled;
}
static int constant(void) {
    int first = 5;
    int total = first * 2;
    return total;
}
int main(void) { return calculate(7, 14) == 42 ? 0 : 1; }
