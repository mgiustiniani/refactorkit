typedef int Width;
int seed = 7;
static int answer(/* left */ void /* right */);
static int answer(void) { Width value = seed; return value + sizeof(Width); }
int main(void) { return answer(); }
