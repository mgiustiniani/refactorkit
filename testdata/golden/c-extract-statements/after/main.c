static int computeTotal(int a, int b) {
    int sum = a + b;
    int doubled = sum * 2;
    return doubled;
}

static int calculate(int a, int b) {
    int doubled = computeTotal(a, b);
    return doubled;
}
int main(void) { return calculate(7, 14) == 42 ? 0 : 1; }
