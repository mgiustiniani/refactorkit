static int weighted(int amount, int right);
static int weighted(int amount, int right) { return amount * 10 + right; }
static int unrelated(int amount) { return amount; }
int main(void) { return weighted(4, 2) == 42 ? 0 : 1; }
