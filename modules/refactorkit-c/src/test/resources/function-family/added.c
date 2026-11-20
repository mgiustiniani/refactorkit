static int weighted(int x, int limit, int y, int z);
static int weighted(int a, int limit, int b, int c) { return a * 100 + b * 10 + c; }
int main(void) { int a = 9; return weighted(a, (5), 8, 7) + weighted(1, (3), 2, 3); }
static int unrelated(void) { int weighted = 4; return weighted; }
