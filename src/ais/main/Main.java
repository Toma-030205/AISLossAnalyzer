package ais.main;

import ais.parser.FileLoader;

public class Main {

    public static void main(String[] args) {

        String filePath = "C:/Users/Owner/AISData/260401-0.ais";

        FileLoader loader = new FileLoader();

        loader.loadFile(filePath);
    }
}