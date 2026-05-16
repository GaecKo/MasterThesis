import pandas as pd

df = pd.read_csv("all_results/b_cpu4.csv")
print(max(df["Requests/s"]))