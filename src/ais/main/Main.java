package main;

import parser.FileLoader;

public class Main {

    public static void main(String[] args) {

        // .aisファイルのパス
        String filePath = "C:\\Users\\Owner\\AISData\\260401-0.ais";

        FileLoader loader = new FileLoader();

        loader.loadFile(filePath);
    }
}