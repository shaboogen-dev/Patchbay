# Bundled fonts

The window bundles two fonts under the SIL Open Font License 1.1. They are third-party files, so git ignores them; put them here before building (`loader/build.py` bundles them into the window jar, and warns and falls back to the platform's fonts without them).

| File | Source |
|---|---|
| Outfit-Regular.ttf, Outfit-SemiBold.ttf, OFL-Outfit.txt | https://github.com/Outfitio/Outfit-Fonts (fonts/ttf, OFL.txt), main branch, 9 Nov 2026 |
| JetBrainsMono-Regular.ttf, OFL-JetBrainsMono.txt | https://github.com/JetBrains/JetBrainsMono (fonts/ttf, OFL.txt), master branch, 9 Nov 2026 |

SHA-256:

```
e6fd0d7e91550b3ed2b735d4312474362c4716edc4fc0577a0f61ed782d5aed1 *JetBrainsMono-Regular.ttf
3b64ac4f6ab6a8eebddd4b0bc03c811c43602e11e176382ab0ee6be615ab861b *Outfit-Regular.ttf
bf2e1d2a6ec2a67952e8b36edd2b2bb9f340c0cdd10b0ad5145b4dbbc1339608 *Outfit-SemiBold.ttf
a76abf002c49097d146e86740a3105a5d00450b1592e820a1109a8c5680cd697 *OFL-JetBrainsMono.txt
c676351bf8576b9aba743cd5eaa8c0e7ee0d51f805d720447b4df4ddb6a2e416 *OFL-Outfit.txt
```
