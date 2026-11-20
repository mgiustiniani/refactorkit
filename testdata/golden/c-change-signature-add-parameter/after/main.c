static int weighted(int x, int limit, int y);
static int weighted(int a, int limit, int b) { return a * 10 + b; }
int main(void) { return weighted(9, (7), 8); }
