static int weighted(int z, int y, int x);
static int weighted(int c, int b, int a) { return a * 100 + b * 10 + c; }
int main(void) { return weighted(7, 8, 9); }
