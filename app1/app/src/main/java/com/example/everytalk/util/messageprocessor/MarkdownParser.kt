package  com.example.everytalk.util.messageprocessor

import  com.example.everytalk.ui.components.MarkdownPart

/**
  *  Minimal  markdown  math  parser  for  unit  tests.
  *  Detects:
  *  -  ```math ... ```       (display  math)
  *  -  $$ ... $$          (display  math)
  *  -  \[ ... \]          (display  math)
  *  -  $ ... $           (inline  math)
  *  -  \( ... \)          (inline  math)
  *
  *  Returns  a  single  MathBlock  if  any  of  the  patterns  is  found,
  *  or  empty  list  otherwise.
  */
fun  parseMarkdownParts(text:  String):  List<MarkdownPart>  {
   if  (text.isBlank())  return  emptyList()
   val  t  =  text.trim()

   //  1)  Fenced  math:  ```math\n...\n```
   val  fencedMath  =  Regex("```\\s*math\\s*\\n([\\s\\S]*?)\\n```",  RegexOption.IGNORE_CASE).find(t)
   if  (fencedMath  !=  null)  {
     val  latex  =  fencedMath.groupValues[1].trim()
     return  listOf(MarkdownPart.MathBlock(id  =  "m-1",  latex  =  latex,  displayMode  =  true))
   }

   //  2)  Display  math:  $$  ...  $$
   val  doubleDollar  =  Regex("\\$\\$([\\s\\S]*?)\\$\\$").find(t)
   if  (doubleDollar  !=  null)  {
     val  latex  =  doubleDollar.groupValues[1].trim()
     return  listOf(MarkdownPart.MathBlock(id  =  "m-1",  latex  =  latex,  displayMode  =  true))
   }

   //  3)  Display  math:  \[  ...  \]
   val  bracketBlock  =  Regex("\\\\\\[([\\s\\S]*?)\\\\\\]").find(t)
   if  (bracketBlock  !=  null)  {
     val  latex  =  bracketBlock.groupValues[1].trim()
     return  listOf(MarkdownPart.MathBlock(id  =  "m-1",  latex  =  latex,  displayMode  =  true))
   }

   //  4)  Inline  math:  $  ...  $
   val  inlineDollar  =  Regex("\\$([^$\\n]+)\\$").find(t)
   if  (inlineDollar  !=  null)  {
     val  latex  =  inlineDollar.groupValues[1].trim()
     return  listOf(MarkdownPart.MathBlock(id  =  "m-1",  latex  =  latex,  displayMode  =  false))
   }

   //  5)  Inline  math:  \(  ...  \)
   val  inlineParen  =  Regex("\\\\\\(([^)]+)\\\\\\)").find(t)
   if  (inlineParen  !=  null)  {
     val  latex  =  inlineParen.groupValues[1].trim()
     return  listOf(MarkdownPart.MathBlock(id  =  "m-1",  latex  =  latex,  displayMode  =  false))
   }

   return  emptyList()
}