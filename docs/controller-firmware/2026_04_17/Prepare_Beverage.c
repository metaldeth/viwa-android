//=================== Prepare Beverage ==========================================
 if(prep_bevrg_stage==SELECT_BVRG)     // stage 1
   {
     delay_ms(20);
     PORTB=code_s[28]; // display C symbol on 7 segment system LED  
      Start_Of_Prepare_Beverage();   // Message to PC 
          // reset flags and error 
           Error=0;
          // b_water_analize=0; 
           LED_M1=1;
           Mors_Syrup_Routine1();
           
   }
    

//=============================================================================== 
//===============================================================================  
//===============================================================================  
//           MAIN ROUTINE - SYRUP                                        
//===============================================================================
//===============================================================================
//=============================================================================== 
//===============================================================================
 // For Syrup
 if(prep_bevrg_stage==COOK_BVRG_WATER_MAIN)  //only water
   {
    if(water_counter_data>=water_counter)
      { 
       // b_analize_glass=0;
       Water_Pump_Valve(0);   // Water pump off
       timer_fault_h=0;
       prep_bevrg_stage=BVRG_OUT_DLAY;
      }
   }

//-------------------------------------------------------------------------------- 
 if(prep_bevrg_stage==COOK_BVRG_NPT_MAIN)          // stage 4_npt
   {
     // ------------ Napitki Peristalic pump -------------------------------------
     
    if(b_stop_syrup==0)
      {
        if(timer_prepare_bevarege_p>=temp_Time_Work_Dozators1)    
          {
           Peristalic_Pump_On_Off(0);
           b_stop_syrup=1;
          }   
      }               
    
     //============================================================================    
       
     if(b_stop_water==0)
       {
         if(water_counter_data>=water_counter)  
           { 
              Water_Pump_Valve(0);   // Water pump off   
              Klap_Data(0);  // peristaltic water valve 
              Peristalic_Pump_On_Off(0);
            
              //b_water_analize=0;
              
              b_stop_water=1;
           }
       }
     
     //--------------------------------------------------------------------
      
     if((b_stop_water==1)&&(b_stop_syrup==1))
       { 
        // b_water_analize=0; 
       
         Peristalic_Pump_On_Off(0);
          Water_Pump_Valve(0);   // Water pump off  
           Klap_Data(0);  // peristaltic water valve 
        
         prep_bevrg_stage=COOK_NPT_END;
       } 
      
      
   } 
 //=================================================================================  
 if(prep_bevrg_stage==COOK_BVRG_MERGE)
   {
    if(timer_prepare_bevarege_p>=60)   // 6 sec past    
      {
         Water_Pump_Valve(0);   // Water pump off 
          Klap_Data(0);  // peristaltic water valve off
        
       // b_water_analize=0; 
             
        prep_bevrg_stage=COOK_BVRG_MERGE2;     
                       
      }   
   } 

//====================================================================================   
if(prep_bevrg_stage==COOK_BVRG_MERGE2)
  {
    if(timer_prepare_bevarege_p>=delta_time_cook+60)   //Delta time cook + 6 sec past    
      {
         Water_Pump_Valve(1);   // Water pump on 
         // type of water 0->water from bottle, 1->cold water, 2 ->cold gas water    water valves
        if(Type_of_water==0)  {Klap_Data(1);  Water_Pump_Valve(1);} // 0-> filtered water valve   
        if(Type_of_water==1)  {Klap_Data(2);  Water_Pump_Valve(1);} // 1-> cold water,   
        if(Type_of_water==2)  {Klap_Data(3);  Water_Pump_Valve(1);} // 2 -> cold gas water
           
        b_stop_water=0;
        b_stop_syrup=0; 
       // b_water_analize=1;  
        prep_bevrg_stage=COOK_BVRG_NPT_MAIN;
      }   
  }
//========================================================================================================= 
//=========================================================================================================            
             
 if(prep_bevrg_stage==COOK_NPT_END)       
   {            
      
      if(current_container2==0)
         {
          timer_fault_h=0;
          prep_bevrg_stage=BVRG_OUT_DLAY;
         }     
      
      if(current_container2>0)
        {
         // For Mors/Syrup
          Mors_Syrup_Routine2();    
        } 
        
   }
  
 //===============================================================================
 //===============================================================================
  
 
 //==========================================================-============================
 // For Syrup
 //---------------------------------------------------------------------------------------
 if(prep_bevrg_stage==COOK_BVRG_NPT_ADD)          // stage 4_npt
   {
     // ------------ Napitki Peristalic pump -------------------------------------
     
    if(b_stop_syrup==0)
      {
        if(timer_prepare_bevarege_p>=temp_Time_Work_Dozators2)    
          {
           Peristalic_Pump_On_Off(0);
           b_stop_syrup=1;
          }   
      }               
    
     //============================================================================    
       
     if(b_stop_water==0)
       {
         if(water_counter_data>=water_counter)  
           { 
              Water_Pump_Valve(0);   // Water pump off  
              Klap_Data(0);  // peristaltic water valve 
            
              //b_water_analize=0;
              
              b_stop_water=1;
           }
       }
     
     //--------------------------------------------------------------------
      
     if((b_stop_water==1)&&(b_stop_syrup==1))
       { 
        // b_water_analize=0;
         Peristalic_Pump_On_Off(0);
          Water_Pump_Valve(0);   // Water pump off  
           Klap_Data(0);  // peristaltic water valve 
        
         prep_bevrg_stage=COOK_NPT_END;
       } 
      
    
   }  //prep_bevrg_stage==COOK_BVRG_NPT
   
//================================================================================ 
//================================================================================ 
//================================================================================ 
 if(prep_bevrg_stage==BVRG_OUT_DLAY)     // stage 5
   {
    //b_water_analize=0;
    // b_water_monitor=0;   // disable water monitor 
    //  counter_t_delay=0;   // blocked analize water 
          
       LED_M1=0;   
       Error=0;             //  No Errors 
        timer_fault_h=0;   // reset fault timer
          b_selected_pbv=0;
            End_Of_Prepare_Beverage();   // Message to PC  
             delay_ms(20);
               prep_bevrg_stage=STOP;
                Exit_Selector();
               
   }
//================================================================================ 
   
//================================================================================ 

//================== End of Prepare Beverage =====================================