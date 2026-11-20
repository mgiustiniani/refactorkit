static int weighted(int total, int right);
static int weighted(int total, int right) { return total * 10 + right; }
static int unrelated(int amount) { return amount; }
int main(void) { return weighted(4, 2) == 42 ? 0 : 1; }
